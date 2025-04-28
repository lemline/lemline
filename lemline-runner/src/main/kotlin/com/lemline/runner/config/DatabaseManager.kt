package com.lemline.runner.config

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

@ApplicationScoped
class DatabaseManager {
    private val log = logger()

    @Inject
    private lateinit var defaultDataSource: Instance<AgroalDataSource>

    @Inject
    private lateinit var config: Config

    @Inject
    @DataSource("postgresql")
    private lateinit var postgresDataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("mysql")
    private lateinit var mysqlDataSource: Instance<AgroalDataSource>

    @Inject
    @ConfigProperty(name = "lemline.database.type")
    internal lateinit var dbType: String

    val datasource: AgroalDataSource by lazy {
        log.debug { "Resolving datasource for type: $dbType" }
        log.debug { "PostgreSQL datasource resolvable: ${postgresDataSource.isResolvable}" }
        log.debug { "MySQL datasource resolvable: ${mysqlDataSource.isResolvable}" }
        log.debug { "Default datasource resolvable: ${defaultDataSource.isResolvable}" }

        when (dbType) {
            DB_TYPE_POSTGRESQL -> {
                if (postgresDataSource.isResolvable) postgresDataSource.get()
                else throw IllegalStateException("PostgreSQL datasource is not available. Check if quarkus.datasource.postgresql.active=true is set.")
            }

            DB_TYPE_MYSQL -> {
                if (mysqlDataSource.isResolvable) mysqlDataSource.get()
                else throw IllegalStateException("MySQL datasource is not available")
            }

            DB_TYPE_IN_MEMORY -> {
                if (defaultDataSource.isResolvable) defaultDataSource.get()
                else throw IllegalStateException("H2 datasource is not available")
            }

            else -> throw IllegalStateException("Unknown datasource '$dbType'")
        }
    }

    val flywayLocations by lazy {
        "classpath:db/migration/" + when (dbType) {
            DB_TYPE_IN_MEMORY -> "h2"
            else -> dbType
        }
    }

    val dataSourceName by lazy {
        when (dbType) {
            DB_TYPE_POSTGRESQL -> "postgresql."
            DB_TYPE_MYSQL -> "mysql."
            DB_TYPE_IN_MEMORY -> ""
            else -> throw IllegalStateException("Unsupported db type: $dbType")
        }
    }

    val flyway: Flyway by lazy {
        val baselineOnMigrate = config.getValue("lemline.database.baseline-on-migrate", Boolean::class.java)
        val url = config.getValue("quarkus.datasource.${dataSourceName}jdbc.url", String::class.java)
        val user = config.getValue("quarkus.datasource.${dataSourceName}username", String::class.java)
        val password = config.getValue("quarkus.datasource.${dataSourceName}password", String::class.java)

        log.debug { "Creating Flyway with:" }
        log.debug { "with url: $url" }
        log.debug { "with user: $user" }
        log.debug { "with password: REDACTED" }
        log.debug { "with baselineOnMigrate: $baselineOnMigrate" }
        log.debug { "with location: $flywayLocations" }


        Flyway.configure()
            .dataSource(url, user, password)
            .locations(flywayLocations)
            .baselineOnMigrate(baselineOnMigrate)
            .load()
    }
}
