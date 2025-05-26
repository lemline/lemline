// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.logger
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

    @Inject
    @ConfigProperty(name = LEMLINE_DATABASE_TYPE)
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
        log.debug { "- PostgreSQL datasource resolvable: ${postgresDataSource.isResolvable}" }
        log.debug { "-      MySQL datasource resolvable: ${mysqlDataSource.isResolvable}" }
        log.debug { "-    Default datasource resolvable: ${h2DataSource.isResolvable}" }

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
}
