package com.lemline.worker.config

import com.lemline.common.logger
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Configuration class for database selection.
 * Supports dynamic switching between PostgreSQL and MySQL based on configuration.
 */
@ApplicationScoped
class DatabaseConfig {

    private val logger = logger()

    @ConfigProperty(name = "lemline.database.type", defaultValue = "postgresql")
    lateinit var databaseType: String

    /**
     * Initializes the database configuration on application startup.
     * Validates the selected database type and logs relevant information.
     */
    fun onStart(@Observes event: StartupEvent) {
        when (databaseType.lowercase()) {
            "postgresql" -> logger.info("Using PostgreSQL database")
            "mysql" -> logger.info("Using MySQL database")
            else -> {
                logger.error("Unsupported database type: $databaseType. Supported types are 'postgresql' and 'mysql'")
                throw IllegalArgumentException("Unsupported database type: $databaseType")
            }
        }
    }

    /**
     * Checks if PostgreSQL is the selected database.
     */
    fun isPostgreSQL(): Boolean = databaseType.lowercase() == "postgresql"

    /**
     * Checks if MySQL is the selected database.
     */
    fun isMySQL(): Boolean = databaseType.lowercase() == "mysql"
} 