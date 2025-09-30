// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.outbox.bases.Cleaner
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * A concrete implementation of the `Scheduler` abstract class responsible for
 * managing retries table cleaning tasks. It schedules and executes periodic cleanup
 * operations for the retries table using a configurable cleaning strategy.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class RetryCleaner : Scheduler() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var retryRepository: RetryRepository

    override val description: String = "Retries table cleaning"

    override val schedulable by lazy {
        Cleaner(
            cleanerRepository = retryRepository,
            cleanerConfig = lemlineConfig.database().tables().parents().cleanup()
        )
    }
}
