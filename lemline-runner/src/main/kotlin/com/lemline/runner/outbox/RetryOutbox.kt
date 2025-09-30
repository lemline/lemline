// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.bases.Outbox
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * This class coordinates retry logic in two main areas:
 * - In workflow execution via [com.lemline.runner.StepByStepRunner]:
 *   - Uses `WorkflowInstance.onRetry()` to handle retries defined by the workflow itself.
 * - In message processing via [com.lemline.runner.messaging.instances.InstanceMessageHandler]:
 *   - `Message<String>.saveAsFailed()` records non-recoverable failures.
 *   - `Message<String>.saveForRetry()` schedules recoverable failures for future retry attempts.
 *
 * Inherits core scheduling functionality from the [Scheduler] class, integrating Quarkus lifecycle
 * events to ensure proper startup and shutdown behavior.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class RetryOutbox : Scheduler() {

    @Inject
    private lateinit var instanceEmitter: InstanceMessageEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var failureRepository: FailureRepository

    @Inject
    private lateinit var outboxRepository: RetryRepository

    override val description: String = "Retries table outbox"

    override val schedulable by lazy {
        Outbox(
            failureRepository = failureRepository,
            outboxRepository = outboxRepository,
            outboxConfig = lemlineConfig.database().tables().retries().outbox(),
        ) { entity: RetryModel ->
            instanceEmitter.send(entity.instanceMessage)
        }
    }
}
