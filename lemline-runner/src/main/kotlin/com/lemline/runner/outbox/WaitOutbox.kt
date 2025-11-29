// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.WaitRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * `WaitOutbox` specializes `AbstractOutbox` to implement the outbox pattern for wait tasks in workflows.
 *
 * It manages the restart of workflow instances after a wait task.
 * Integration occurs via the `WorkflowInstance.onWait` method in [com.lemline.runner.StepByStepRunner].
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class WaitOutbox : AbstractOutbox<WaitModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: WaitRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().wait().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().wait().outbox() }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().wait().cleanup() }

    /**
     * Transform WaitStarted Event → ResumeFromStartedTask Command before sending.
     * This ensures the workflow handler receives a command it can process.
     *
     * Uses idempotent message ID derived from the wait model's ID to ensure
     * duplicate processing produces the same message ID.
     */
    override suspend fun process(entity: WaitModel) {
        // Derive message ID from the wait model's ID
        val messageId = entity.id.derive("-resume")

        instanceEmitter.send(
            InstanceMessage(
                workflowInfo = entity.instanceMessage.workflowInfo,
                workflowState = entity.instanceMessage.workflowState.resume(),
            ),
            messageId
        )
    }
}
