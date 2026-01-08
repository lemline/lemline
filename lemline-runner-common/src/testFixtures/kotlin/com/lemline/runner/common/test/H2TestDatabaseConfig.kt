// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway

/**
 * Test implementation of DatabaseConfig that sets up an H2 in-memory database.
 *
 * Usage:
 * ```kotlin
 * class MyRepositoryTest {
 *     companion object {
 *         private val testDb = TestDatabaseConfig("classpath:db/migration/h2")
 *
 *         @BeforeAll
 *         @JvmStatic
 *         fun setup() {
 *             testDb.migrate()
 *         }
 *
 *         @AfterAll
 *         @JvmStatic
 *         fun teardown() {
 *             testDb.close()
 *         }
 *     }
 *
 *     private val repository = MyRepository().apply {
 *         // Inject the test database config
 *         setDatabaseConfig(testDb)
 *     }
 * }
 * ```
 *
 * @param migrationLocation Flyway migration location (e.g., "classpath:db/migration/h2")
 * @param databaseName Optional database name (defaults to random UUID for isolation)
 */
class H2TestDatabaseConfig(
    private val migrationLocation: String = "classpath:db/migration/h2",
    databaseName: String = UUID.randomUUID().toString().replace("-", "")
) : DatabaseConfig, AutoCloseable {

    override val dbType: DatabaseType = DatabaseType.H2

    private val dataSource: HikariDataSource by lazy {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 5
            minimumIdle = 1
            idleTimeout = 30000
            connectionTimeout = 10000
            maxLifetime = 60000
        }
        HikariDataSource(config)
    }

    private val flyway: Flyway by lazy {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(migrationLocation)
            .load()
    }

    /**
     * Runs database migrations.
     * Call this once before running tests.
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
     * Closes the database connection pool.
     * Call this after all tests are complete.
     */
    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }

    override suspend fun <R> withConnection(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> dataSource.connection.use { block(it) }
            else -> block(connection)
        }
    }

    override suspend fun <R> withTransaction(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> {
                dataSource.connection.use { conn ->
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
