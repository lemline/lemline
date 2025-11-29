// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.WorkflowId
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * `ScheduleOutbox` specializes `AbstractOutbox` to implement the outbox pattern for scheduled workflow executions.
 *
 * It handles publishing messages when a scheduled workflow is triggered.
 * Supported schedule types:
 * - cron: Schedules workflow execution based on a cron expression.
 * - every: Executes the workflow at fixed intervals.
 * - after: Triggers the workflow after a previous workflow completes,
 *   managed via `WorkflowInstance.onWorkflowCompleted()` in [com.lemline.runner.StepByStepRunner].
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ScheduleOutbox : AbstractOutbox<ScheduleModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: ScheduleRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().schedule().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().schedule().outbox() }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().schedule().cleanup() }

    /**
     * Process scheduled workflow by updating next execution time and sending command.
     * No transformation needed - ScheduleOutboxModel already stores WorkflowCommand.
     *
     * Uses idempotent message ID derived from the schedule model's ID + scheduled time
     * to ensure duplicate processing produces the same message ID and workflow ID.
     */
    override suspend fun process(entity: ScheduleModel) {
        // Derive a deterministic workflow ID from schedule ID + scheduled time
        // This ensures the same schedule entry always produces the same workflow ID
        val scheduledTime = entity.outboxScheduledFor.toString()
        val deterministicWorkflowId = WorkflowId(entity.id.derive("-wf-$scheduledTime"))

        // Update the schedule model with the next instant to be processed
        entity.prepareNextScheduled(deterministicWorkflowId)

        // Derive message ID from schedule ID + scheduled time
        val messageId = entity.id.derive("-msg-$scheduledTime")

        // Start a new instance of the workflow (instanceMessage already contains WorkflowCommand)
        instanceEmitter.send(entity.instanceMessage, messageId)
    }
}
