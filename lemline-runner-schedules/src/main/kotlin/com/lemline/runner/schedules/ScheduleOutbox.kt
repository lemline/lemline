// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
 *   managed via `WorkflowInstance.onWorkflowCompleted()` in StepByStepRunner.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ScheduleOutbox : AbstractOutbox<ScheduleModel>() {

    override val jobName: String get() = "Schedules outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var scheduleConfig: ScheduleConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    override val outboxRepository: WithOutboxRepository<ScheduleModel> get() = scheduleRepository

    override val crudRepository: WithCrudRepository<ScheduleModel> get() = scheduleRepository

    // Is this outbox enabled?
    override val enabled by lazy { scheduleConfig.enabled }

    // Outbox processing configuration
    override val outboxConfig: OutboxConfig? by lazy { scheduleConfig.outbox }

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
        commandEmitter.send(entity.instanceMessage, messageId)
    }
}
