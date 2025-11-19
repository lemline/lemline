// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * `RetryOutbox` specializes `AbstractOutbox` to implement the outbox pattern for retrying failed operations.
 *
 * This class coordinates retry logic in two main areas:
 * - In workflow execution via [com.lemline.runner.StepByStepRunner]:
 *   - Uses `WorkflowInstance.onRetry()` to handle retries defined by the workflow itself.
 * - In message processing via [com.lemline.runner.messaging.commands.WorkflowCommandHandler]:
 *   - `Message<String>.saveAsFailed()` records non-recoverable failures.
 *   - `Message<String>.saveForRetry()` schedules recoverable failures for future retry attempts.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class RetryOutbox : AbstractOutbox<RetryOutboxModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: RetryRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().retry().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().workflows().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().retry().outbox() }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().retry().cleanup() }

    /**
     * Transform RetryScheduled Event → ResumeFromTask Command before sending.
     * This ensures the workflow handler receives a command it can process.
     */
    override suspend fun process(entity: RetryOutboxModel) {
        val command = entity.instanceMessage.workflowState.resume()
        instanceEmitter.send(
            InstanceMessage(
                workflowInfo = entity.instanceMessage.workflowInfo,
                workflowState = command,
                hasParentWaiting = entity.instanceMessage.hasParentWaiting
            )
        )
    }
}
