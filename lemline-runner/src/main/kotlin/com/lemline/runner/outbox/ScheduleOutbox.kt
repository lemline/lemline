// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.instances.InstanceMessageEmitter
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime

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
internal class ScheduleOutbox : AbstractOutbox<ScheduleOutboxModel>() {

    @Inject
    override lateinit var instanceEmitter: InstanceMessageEmitter

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
            ?: lemlineConfig.messaging().workflows().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().schedule().outbox() }

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().schedule().cleanup() }

    override suspend fun process(entity: ScheduleOutboxModel) {
        // update the schedule model with the next instant to be processed
        entity.prepareNextScheduled()
        // start a new instance of the workflow (with new workflowId)
        super.process(entity)
    }
}
