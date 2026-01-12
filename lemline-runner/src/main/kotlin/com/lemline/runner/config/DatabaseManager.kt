// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.trace
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MigrationManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

@ApplicationScoped
class DatabaseManager : DatabaseConfig, MigrationManager {
    private val log = logger()

    @ConfigProperty(name = DATABASE_TYPE)
    private lateinit var _dbTypeConfig: String

    override val dbType: DatabaseType by lazy {
        when (_dbTypeConfig) {
            DB_TYPE_POSTGRESQL -> DatabaseType.POSTGRESQL
            DB_TYPE_MYSQL -> DatabaseType.MYSQL
            DB_TYPE_IN_MEMORY -> DatabaseType.H2
            else -> throw IllegalStateException("Unknown database type '$_dbTypeConfig'")
        }
    }

    @Inject
    @IfBuildProfile("test")
    internal lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("postgresql")
    internal lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    internal lateinit var mysqlDataSource: Instance<AgroalDataSource>

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
        flyway.migrate()
    }

    /**
     * Returns the database-specific JSON array aggregation function.
     *
     * This function handles the case where the column contains JSON data stored as a string.
     * The aggregation properly parses the string as JSON before aggregating.
     *
     * - PostgreSQL: json_agg(column::json) - casts string to json before aggregation
     * - MySQL: JSON_ARRAYAGG(CAST(column AS JSON)) - casts string to JSON
     * - H2: JSON_ARRAYAGG(column FORMAT JSON) - tells H2 the column is JSON
     *
     * @param column The column expression containing JSON strings to aggregate
     * @return SQL fragment for JSON array aggregation
     */
    override fun jsonArrayAgg(column: String): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "json_agg($column::json)"
        DatabaseType.MYSQL -> "JSON_ARRAYAGG(CAST($column AS JSON))"
        DatabaseType.H2 -> "JSON_ARRAYAGG($column FORMAT JSON)"
    }

    /**
     * Returns the database-specific JSON array aggregation function with ordering.
     *
     * - PostgreSQL: json_agg(column::json ORDER BY orderColumn)
     * - MySQL: JSON_ARRAYAGG(CAST(column AS JSON) ORDER BY orderColumn)
     * - H2: JSON_ARRAYAGG(column FORMAT JSON ORDER BY orderColumn)
     *
     * @param column The column expression containing JSON strings to aggregate
     * @param orderColumn The column to order by
     * @return SQL fragment for ordered JSON array aggregation
     */
    override fun jsonArrayAggOrdered(column: String, orderColumn: String): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "json_agg($column::json ORDER BY $orderColumn)"
        DatabaseType.MYSQL -> "JSON_ARRAYAGG(CAST($column AS JSON) ORDER BY $orderColumn)"
        DatabaseType.H2 -> "JSON_ARRAYAGG($column FORMAT JSON ORDER BY $orderColumn)"
    }

    /**
     * Returns the database-specific random UUID generation function.
     *
     * - PostgreSQL: gen_random_uuid()
     * - MySQL: UUID_TO_BIN(UUID())
     * - H2: RANDOM_UUID()
     *
     * @return SQL fragment for generating a random UUID
     */
    fun randomUuid(): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "gen_random_uuid()"
        DatabaseType.MYSQL -> "UUID_TO_BIN(UUID())"
        DatabaseType.H2 -> "RANDOM_UUID()"
    }

    /**
     * Returns the database-specific INSERT ON CONFLICT/IGNORE syntax for INSERT...SELECT.
     *
     * - PostgreSQL/H2: INSERT ... ON CONFLICT DO NOTHING
     * - MySQL: INSERT IGNORE INTO ...
     *
     * @param tableName The table name
     * @param columns The columns to insert
     * @param selectSql The SELECT SQL to insert from
     * @return Full INSERT...SELECT SQL with conflict handling
     */
    override fun insertIgnoreSelect(tableName: String, columns: String, selectSql: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) $selectSql"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) $selectSql ON CONFLICT DO NOTHING"
    }

    /**
     * Returns the database-specific INSERT ON CONFLICT/IGNORE syntax for INSERT...VALUES.
     *
     * - PostgreSQL/H2: INSERT INTO ... VALUES (...) ON CONFLICT DO NOTHING
     * - MySQL: INSERT IGNORE INTO ... VALUES (...)
     *
     * @param tableName The table name
     * @param columns The columns to insert
     * @param values The VALUES clause (with placeholders)
     * @return Full INSERT...VALUES SQL with conflict handling
     */
    override fun insertIgnoreInto(tableName: String, columns: String, values: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) VALUES ($values)"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) VALUES ($values) ON CONFLICT DO NOTHING"
    }

    /**
     * Returns a NULL value explicitly cast to timestamp type.
     * PostgreSQL requires explicit casts in CASE expressions when one branch is NULL.
     *
     * @return Database-specific NULL::timestamp expression
     */
    fun nullTimestamp(): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "NULL::TIMESTAMPTZ"
        DatabaseType.MYSQL, DatabaseType.H2 -> "NULL"
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
