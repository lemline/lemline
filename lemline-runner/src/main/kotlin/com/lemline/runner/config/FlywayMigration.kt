// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.runner.common.config.DatabaseType
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Ensures that Flyway database migrations are applied during application startup.
 *
 * Runs at priority 1, after DatabaseStartupValidator (priority 0) has verified connectivity.
 */
@ApplicationScoped
class FlywayMigration(
    @param:ConfigProperty(name = "quarkus.profile")
    val profile: String,

    @param:ConfigProperty(name = DATABASE_TYPE)
    val db: String,

    @param:ConfigProperty(name = MIGRATE_AT_START)
    val migrateAtStart: Boolean,

    @param:ConfigProperty(name = DATABASE_ENABLED, defaultValue = "true")
    val databaseEnabled: Boolean,
) {
    private val logger = logger()

    @Inject
    lateinit var databaseManager: DatabaseManager

    @Suppress("unused")
    fun onStart(@Observes @Priority(1) event: StartupEvent) {
        // Skip database initialization if disabled (e.g., for --help or --version)
        if (!databaseEnabled) return

        // Run migrations:
        // - if the profile is "test" - as databases are recreated for each test
        // - if the database type is in-memory - as it is provided by the app
        // - if migrateAtStart is true - as it is requested by the user
        if (profile == "test" || db == DatabaseType.H2.configValue || migrateAtStart) {
            databaseManager.flyway.migrate()
            logger.info { "Flyway migrations applied successfully on ${databaseManager.dbType} database." }
        } else {
            // Check for pending migrations and fail with clear message if found
            verifyMigrationsApplied()
        }
    }

    /**
     * Checks if all migrations have been applied.
     * Logs a warning if pending migrations are found, allowing the app to continue.
     */
    private fun verifyMigrationsApplied() {
        val pendingMigrations = databaseManager.flyway.info().pending()

        if (pendingMigrations.isNotEmpty()) {
            val migrationList = pendingMigrations.joinToString("\n") { migration ->
                "  - ${migration.version}: ${migration.description}"
            }

            logger.warn {
                """
                |
                |Database schema is not up to date. ${pendingMigrations.size} migration(s) pending:
                |$migrationList
                |
                |To apply migrations, either:
                |  - Run: lemline migrate
                |  - Or set 'migrate-at-start: true' in your .lemline.yaml
                """.trimMargin()
            }
        } else {
            logger.info { "Database schema is up to date." }
        }
    }
}
