// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

/**
 * Ensures that Flyway database migrations are applied during application startup
 * After applying the LemlineConfigSourceFactory,
 */
@ApplicationScoped
class FlywayMigration(
    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev")
    val profile: String,

    @ConfigProperty(name = "lemline.database.type")
    val db: String,

    @ConfigProperty(name = "quarkus.flyway.migrate-at-start")
    val migrateAtStart: Boolean,
) {
    @Inject
    private lateinit var flyway: Flyway

    fun onStart(@Observes event: StartupEvent) {
        if (profile == "test" || db == DB_TYPE_IN_MEMORY || migrateAtStart) {
            flyway.migrate()
        }
    }
}
