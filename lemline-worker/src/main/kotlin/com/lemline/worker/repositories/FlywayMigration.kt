// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

/**
 * Ensures that Flyway database migrations are applied during application startup
 * After applying the LemlineConfigSourceFactory,
 * specifically when running in the 'test' profile.
 *
 * This is crucial for integration tests that rely on the database schema being
 * up-to-date. By observing the `StartupEvent` and checking if the profile is 'test',
 * it triggers `flyway.migrate()` to execute any pending migrations before tests run.
 * This guarantees a consistent database state for testing purposes.
 */
@ApplicationScoped
class FlywayMigration(
    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev")
    val profile: String,

    @ConfigProperty(name = "lemline.database.type")
    val db: String,

    @ConfigProperty(name = "lemline.database.migrate-at-start")
    val migrateAtStart: Boolean
) {
    @Inject
    lateinit var flyway: Flyway

    fun onStart(@Observes event: StartupEvent) {
        if (profile == "test" || db == DB_TYPE_IN_MEMORY || migrateAtStart) {
            flyway.migrate()
        }
    }
}
