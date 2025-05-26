// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Ensures that Flyway database migrations are applied during application startup
 * After applying the LemlineConfigSourceFactory,
 */
@ApplicationScoped
class FlywayMigration(
    @ConfigProperty(name = "quarkus.profile")
    val profile: String,

    @ConfigProperty(name = LEMLINE_DATABASE_TYPE)
    val db: String,

    @ConfigProperty(name = "lemline.database.migrate-at-start")
    val migrateAtStart: Boolean,
) {
    private val log = logger()

    @Inject
    lateinit var databaseManager: DatabaseManager

    fun onStart(@Observes event: StartupEvent) {
        // Run migrations:
        // - if the profile is "test" - as databases are recreated for each test
        // - if the database type is in-memory - as it is provided by the app
        // - if migrateAtStart is true - as it is requested by the user
        if (profile == "test" || db == DB_TYPE_IN_MEMORY || migrateAtStart) {
            // migrate custom Flyway
            databaseManager.flyway.migrate()
            log.info("Flyway migrations applied successfully on ${databaseManager.dbType} database.")
        }
    }
}
