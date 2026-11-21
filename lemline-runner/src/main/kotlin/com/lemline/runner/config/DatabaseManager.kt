// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.trace
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MigrationManager
import com.lemline.runner.common.config.SQLiteDatabase
import com.lemline.runner.common.config.SQLiteMigrationLock
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.flyway.FlywayDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway

@ApplicationScoped
class DatabaseManager(
    private val config: LemlineConfiguration
) : DatabaseConfig, MigrationManager {
    private val log = logger()

    override val dbType: DatabaseType by lazy { DatabaseType.fromConfigValue(config.database().type()) }

    @Inject
    @IfBuildProfile("test")
    internal lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("postgresql")
    internal lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    internal lateinit var mysqlDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("sqlite")
    internal lateinit var sqliteDataSource: Instance<AgroalDataSource>

    val datasource: AgroalDataSource by lazy {
        log.debug { "Resolving datasource for type: $dbType" }
        log.trace { "- PostgreSQL datasource resolvable: ${postgresDataSource.isResolvable}" }
        log.trace { "-      MySQL datasource resolvable: ${mysqlDataSource.isResolvable}" }
        log.trace { "-    Default datasource resolvable: ${h2DataSource.isResolvable}" }

        when (dbType) {
            DatabaseType.POSTGRESQL -> {
                if (postgresDataSource.isResolvable) postgresDataSource.get()
                else throw IllegalStateException("PostgreSQL datasource is not available.")
            }

            DatabaseType.MYSQL -> {
                if (mysqlDataSource.isResolvable) mysqlDataSource.get()
                else throw IllegalStateException("MySQL datasource is not available")
            }

            DatabaseType.SQLITE -> sqliteDataSource.get().also { pool ->
                pool.connection.use(SQLiteDatabase::initialize)
            }

            DatabaseType.H2 -> {
                if (h2DataSource.isResolvable) h2DataSource.get()
                else throw IllegalStateException("H2 datasource is not available")
            }
        }
    }

    @Inject
    @IfBuildProfile("test")
    internal lateinit var h2Flyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("postgresql")
    internal lateinit var postgresqlFlyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("mysql")
    internal lateinit var mysqlFlyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("sqlite")
    internal lateinit var sqliteFlyway: Instance<Flyway>

    val flyway: Flyway by lazy {
        log.debug { "Resolving flyway for type: $dbType" }
        log.debug { "- PostgreSQL flyway resolvable: ${postgresqlFlyway.isResolvable}" }
        log.debug { "-      MySQL flyway resolvable: ${mysqlFlyway.isResolvable}" }
        log.debug { "-         H2 flyway resolvable: ${h2Flyway.isResolvable}" }

        when (dbType) {
            DatabaseType.POSTGRESQL -> {
                if (postgresqlFlyway.isResolvable) postgresqlFlyway.get()
                else throw IllegalStateException("PostgreSQL flyway is not available.")
            }

            DatabaseType.MYSQL -> {
                if (mysqlFlyway.isResolvable) mysqlFlyway.get()
                else throw IllegalStateException("MySQL flyway is not available")
            }

            DatabaseType.SQLITE -> {
                // Also initialize WAL when the CLI invokes Flyway before runtime hooks.
                check(datasource.isHealthy(false)) { "SQLite datasource is unavailable" }
                sqliteFlyway.get()
            }

            DatabaseType.H2 -> {
                if (h2Flyway.isResolvable) h2Flyway.get()
                else throw IllegalStateException("H2 flyway is not available")
            }
        }
    }

    // ========================================
    // MigrationManager Implementation
    // ========================================

    override fun getPendingMigrations(): List<MigrationManager.MigrationInfo> {
        return flyway.info().pending().map { migration ->
            MigrationManager.MigrationInfo(
                version = migration.version?.version,
                description = migration.description,
                state = migration.state.displayName,
                installedOn = migration.installedOn?.toString()
            )
        }
    }

    override fun getAllMigrations(): List<MigrationManager.MigrationInfo> {
        return flyway.info().all().map { migration ->
            MigrationManager.MigrationInfo(
                version = migration.version?.version,
                description = migration.description,
                state = migration.state.displayName,
                installedOn = migration.installedOn?.toString()
            )
        }
    }

    override fun migrate() {
        if (dbType == DatabaseType.SQLITE) {
            // Flyway takes no lock on SQLite: serialize the processes sharing the file.
            val url = datasource.connection.use { it.metaData.url }
            SQLiteMigrationLock.withLock(url) { flyway.migrate() }
        } else {
            flyway.migrate()
        }
    }

    override fun jsonArrayAggOrdered(column: String, orderColumn: String): String =
        if (dbType == DatabaseType.MYSQL) {
            "JSON_ARRAYAGG(CAST($column AS JSON) ORDER BY $orderColumn)"
        } else {
            super.jsonArrayAggOrdered(column, orderColumn)
        }

    /**
     * Executes a block of code with a database connection.
     * If an existing connection is provided, it will be reused.
     * Otherwise, a new connection is obtained from the pool and closed after use.
     *
     * @param connection Optional existing connection to reuse
     * @param block The code block to execute with the connection
     * @return The result of the block execution
     */
    override suspend fun <R> withConnection(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> datasource.connection.use { block(it) }
            else -> block(connection)
        }
    }

    /**
     * Executes a block of code within a database transaction.
     * If an existing connection is provided, it will be reused (assuming it's already in a transaction).
     * Otherwise, a new connection is obtained, auto-commit is disabled, and the transaction
     * is committed on success or rolled back on failure.
     *
     * @param connection Optional existing connection to reuse (must already be in transaction mode)
     * @param block The code block to execute within the transaction
     * @return The result of the block execution
     * @throws Throwable Rethrows any exception after rolling back the transaction
     */
    override suspend fun <R> withTransaction(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> {
                datasource.connection.use { conn ->
                    if (dbType == DatabaseType.SQLITE) return@withContext SQLiteDatabase.writeTransaction(conn, block)
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
