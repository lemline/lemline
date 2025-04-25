// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

@ApplicationScoped
class TestFlywayMigration(
    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev")
    val profile: String
) {
    @Inject
    lateinit var flyway: Flyway

    fun onStart(@Observes event: StartupEvent) {
        if (profile == "test") {
            flyway.migrate()
        }
    }
}
