// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.common.config.DatabaseType
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.sql.SQLException
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Validates database connectivity during application startup.
 * Provides clear, actionable error messages when the database is unreachable.
 *
 * Runs at priority 0, before FlywayMigration (priority 1) and MessagingStartupValidator (priority 2).
 */
@ApplicationScoped
class DatabaseStartupValidator(
    @param:ConfigProperty(name = DATABASE_TYPE)
    val db: String,

    @param:ConfigProperty(name = DATABASE_ENABLED, defaultValue = "true")
    val databaseEnabled: Boolean,
) {
    private val logger = logger()

    private val dbType: DatabaseType by lazy {
        DatabaseType.fromConfigValue(db)
    }

    @Inject
    lateinit var databaseManager: DatabaseManager

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        // Skip validation if database is disabled (e.g., for --help or --version)
        if (!databaseEnabled) return

        // Skip validation for in-memory database (always available)
        if (dbType == DatabaseType.H2) {
            logger.info { "Using in-memory database - skipping connectivity check." }
            return
        }

        verifyDatabaseConnectivity()
    }

    /**
     * Verifies that the database is reachable.
     * Throws a clear error message if the connection fails.
     */
    private fun verifyDatabaseConnectivity() {
        try {
            databaseManager.datasource.connection.use { conn ->
                conn.isValid(5) // 5 second timeout
            }
            logger.info { "Database connectivity verified for $dbType." }
        } catch (e: SQLException) {
            throw DatabaseStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  DATABASE CONNECTION FAILED
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Unable to connect to the $dbType database.
                |
                |  Please verify:
                |    • The database server is running
                |    • The connection settings in .lemline.yaml are correct
                |    • The database user has appropriate permissions
                |
                |  Error: ${e.message}
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin(),
                e
            )
        }
    }
}

/**
 * Exception thrown when database startup validation fails.
 * The message is designed to be user-friendly and actionable.
 */
class DatabaseStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
