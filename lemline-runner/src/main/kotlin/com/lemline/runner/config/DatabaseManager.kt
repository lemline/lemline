// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.trace
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

@ApplicationScoped
class DatabaseManager {
    private val log = logger()

    @ConfigProperty(name = DATABASE_TYPE)
    internal lateinit var dbType: String

    @Inject
    @IfBuildProfile("test")
    private lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("postgresql")
    private lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    private lateinit var mysqlDataSource: Instance<AgroalDataSource>

    val datasource: AgroalDataSource by lazy {
        log.debug { "Resolving datasource for type: $dbType" }
        log.trace { "- PostgreSQL datasource resolvable: ${postgresDataSource.isResolvable}" }
        log.trace { "-      MySQL datasource resolvable: ${mysqlDataSource.isResolvable}" }
        log.trace { "-    Default datasource resolvable: ${h2DataSource.isResolvable}" }

        when (dbType) {
            DB_TYPE_POSTGRESQL -> {
                if (postgresDataSource.isResolvable) postgresDataSource.get()
                else throw IllegalStateException("PostgreSQL datasource is not available.")
            }

            DB_TYPE_MYSQL -> {
                if (mysqlDataSource.isResolvable) mysqlDataSource.get()
                else throw IllegalStateException("MySQL datasource is not available")
            }

            DB_TYPE_IN_MEMORY -> {
                if (h2DataSource.isResolvable) h2DataSource.get()
                else throw IllegalStateException("H2 datasource is not available")
            }

            else -> throw IllegalStateException("Unknown database type '$dbType'")
        }
    }

    @Inject
    @IfBuildProfile("test")
    private lateinit var h2Flyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("postgresql")
    private lateinit var postgresqlFlyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("mysql")
    private lateinit var mysqlFlyway: Instance<Flyway>

    val flyway: Flyway by lazy {
        log.debug { "Resolving flyway for type: $dbType" }
        log.debug { "- PostgreSQL flyway resolvable: ${postgresqlFlyway.isResolvable}" }
        log.debug { "-      MySQL flyway resolvable: ${mysqlFlyway.isResolvable}" }
        log.debug { "-         H2 flyway resolvable: ${h2Flyway.isResolvable}" }

        when (dbType) {
            DB_TYPE_POSTGRESQL -> {
                if (postgresqlFlyway.isResolvable) postgresqlFlyway.get()
                else throw IllegalStateException("PostgreSQL flyway is not available.")
            }

            DB_TYPE_MYSQL -> {
                if (mysqlFlyway.isResolvable) mysqlFlyway.get()
                else throw IllegalStateException("MySQL flyway is not available")
            }

            DB_TYPE_IN_MEMORY -> {
                if (h2Flyway.isResolvable) h2Flyway.get()
                else throw IllegalStateException("H2 flyway is not available")
            }

            else -> throw IllegalStateException("Unknown database type '$dbType'")
        }
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
    fun jsonArrayAgg(column: String): String = when (dbType) {
        DB_TYPE_POSTGRESQL -> "json_agg($column::json)"
        DB_TYPE_MYSQL -> "JSON_ARRAYAGG(CAST($column AS JSON))"
        else -> "JSON_ARRAYAGG($column FORMAT JSON)"
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
        DB_TYPE_POSTGRESQL -> "gen_random_uuid()"
        DB_TYPE_MYSQL -> "UUID_TO_BIN(UUID())"
        else -> "RANDOM_UUID()"
    }

    /**
     * Returns the database-specific INSERT ON CONFLICT/IGNORE syntax.
     *
     * - PostgreSQL/H2: INSERT ... ON CONFLICT DO NOTHING
     * - MySQL: INSERT IGNORE INTO ...
     *
     * @param tableName The table name
     * @param columns The columns to insert
     * @param selectSql The SELECT SQL to insert from
     * @return Full INSERT...SELECT SQL with conflict handling
     */
    fun insertIgnoreSelect(tableName: String, columns: String, selectSql: String): String = when (dbType) {
        DB_TYPE_MYSQL -> "INSERT IGNORE INTO $tableName ($columns) $selectSql"
        else -> "INSERT INTO $tableName ($columns) $selectSql ON CONFLICT DO NOTHING"
    }
}
