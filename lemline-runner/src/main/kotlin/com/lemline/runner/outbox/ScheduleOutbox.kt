// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.WorkflowId
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.outbox.bases.Outbox
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ScheduleRepository
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
 *   managed via `WorkflowInstance.onWorkflowCompleted()` in [com.lemline.runner.StepByStepRunner].
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ScheduleOutbox : Scheduler() {

    @Inject
    private lateinit var instanceEmitter: InstanceMessageEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var scheduleRepository: ScheduleRepository

    @Inject
    private lateinit var failureRepository: FailureRepository

    override val description: String = "Schedules table outbox"

    override val schedulable by lazy {
        Outbox(
            failureRepository = failureRepository,
            outboxRepository = scheduleRepository,
            outboxConfig = lemlineConfig.database().tables().schedules().outbox(),
        ) { entity: ScheduleModel ->
            // update the schedule model with the next instant to be processed
            entity.prepareNextScheduled(WorkflowId.random())
            // start a new instance of the workflow (with new workflowId)
            instanceEmitter.send(entity.instanceMessage)
        }
    }
}
