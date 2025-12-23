// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Test implementation of DatabaseConfig that sets up a MySQL database using Testcontainers.
 *
 * Usage:
 * ```kotlin
 * class MyRepositoryTest {
 *     companion object {
 *         private val testDb = MySQLTestDatabaseConfig()
 *
 *         @BeforeAll
 *         @JvmStatic
 *         fun setup() {
 *             testDb.start()
 *             testDb.migrate()
 *         }
 *
 *         @AfterAll
 *         @JvmStatic
 *         fun teardown() {
 *             testDb.close()
 *         }
 *     }
 * }
 * ```
 *
 * @param migrationLocation Flyway migration location (defaults to "classpath:db/migration/mysql")
 */
class MySQLTestDatabaseConfig(
    private val migrationLocation: String = "classpath:db/migration/mysql"
) : DatabaseConfig, AutoCloseable {

    override val dbType: DatabaseType = DatabaseType.MYSQL

    private val container: MySQLContainer<*> by lazy {
        // Use MySQL 8.0.36+ for ORDER BY support in JSON_ARRAYAGG (added in 8.0.14)
        MySQLContainer(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("lemline_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--default-authentication-plugin=mysql_native_password")
    }

    private var dataSource: HikariDataSource? = null

    /**
     * Starts the MySQL container.
     * Must be called before any database operations.
     */
    fun start() {
        container.start()
        dataSource = createDataSource()
    }

    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            driverClassName = "com.mysql.cj.jdbc.Driver"
            username = container.username
            password = container.password
            maximumPoolSize = 5
            minimumIdle = 1
            idleTimeout = 30000
            connectionTimeout = 10000
            maxLifetime = 60000
        }
        return HikariDataSource(config)
    }

    private val flyway: Flyway by lazy {
        Flyway.configure()
            .dataSource(requireDataSource())
            .locations(migrationLocation)
            .load()
    }

    private fun requireDataSource(): HikariDataSource {
        return dataSource ?: throw IllegalStateException(
            "MySQLTestDatabaseConfig not started. Call start() first."
        )
    }

    /**
     * Runs database migrations.
     * Call this once after start() and before running tests.
     */
    fun migrate() {
        flyway.migrate()
    }

    /**
     * Cleans the database (drops all objects).
     * Useful for resetting between test classes.
     */
    fun clean() {
        flyway.clean()
    }

    /**
     * Closes the database connection pool and stops the container.
     * Call this after all tests are complete.
     */
    override fun close() {
        dataSource?.let {
            if (!it.isClosed) {
                it.close()
            }
        }
        if (container.isRunning) {
            container.stop()
        }
    }

    override suspend fun <R> withConnection(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> requireDataSource().connection.use { block(it) }
            else -> block(connection)
        }
    }

    override suspend fun <R> withTransaction(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> {
                requireDataSource().connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        block(conn).also { conn.commit() }
                    } catch (t: Throwable) {
                        conn.rollback()
                        throw t
                    }
                }
            }
            else -> block(connection)
        }
    }
}
