package com.lemline.worker.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway
import org.jboss.logging.Logger

/**
 * Configuration class for Flyway database migrations.
 * Configures migrations based on the selected database type (PostgreSQL or MySQL).
 */
@ApplicationScoped
class FlywayConfig {

    private val logger = Logger.getLogger(FlywayConfig::class.java)

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var datasourceUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var username: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var password: String

    /**
     * Configure Flyway on application startup based on the selected database type.
     */
    fun onStart(@Observes event: StartupEvent) {
        logger.info("Configuring Flyway for database: ${databaseConfig.databaseType}")
        
        val migrationLocations = if (databaseConfig.isMySQL()) {
            arrayOf("classpath:db/migration/mysql")
        } else {
            arrayOf("classpath:db/migration/postgresql")
        }
        
        // Create and configure Flyway instance
        val flyway = Flyway.configure()
            .dataSource(datasourceUrl, username, password)
            .locations(*migrationLocations)
            .load()
            
        // Run migrations
        flyway.migrate()
        
        logger.info("Flyway migrations completed successfully for database: ${databaseConfig.databaseType}")
    }
} 