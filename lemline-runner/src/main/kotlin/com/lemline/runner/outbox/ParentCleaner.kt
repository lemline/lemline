// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.outbox.bases.Cleaner
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.ParentRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * A specialized implementation of the `Scheduler` abstract class designed to periodically clean up the
 * "parents" table in the database. It is configured to automatically execute cleaning tasks based on
 * application properties and initialization logic.
 *
 * The `ParentCleaner` utilizes injected repository and configuration dependencies to determine
 * how data is cleaned and the frequency of the operation.
 *
 * An instance of this class becomes active at application startup and maintains the scheduled
 * cleaning process, implementing a robust mechanism for execution, cancellation, and graceful
 * shutdown when the application terminates.
 *
 * Annotations:
 * - `@Startup`: Marks this class to be initialized at application startup.
 * - `@ApplicationScoped`: Ensures a single instance is created and shared throughout the application.
 * - `@ExperimentalTime`: Marks the usage of Kotlin's experimental time API.
 * - `@ExperimentalSerializationApi`: Indicates the use of experimental serialization features.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentCleaner : Scheduler() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var parentRepository: ParentRepository

    override val description: String = "Parents table cleaning"

    override val schedulable by lazy {
        Cleaner(
            cleanerRepository = parentRepository,
            cleanerConfig = lemlineConfig.database().tables().parents().cleanup()
        )
    }
}
