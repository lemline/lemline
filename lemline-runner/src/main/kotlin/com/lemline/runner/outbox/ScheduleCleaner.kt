// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.outbox.bases.Cleaner
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * `RunWorkflowOutbox` specializes `AbstractOutbox` to implement the outbox pattern for child workflow execution events.
 *
 * It is responsible for publishing messages when:
 * - A child workflow is started via `WorkflowInstance.onRunWorkflow()`
 * - A parent workflow is restarted after completion via `WorkflowInstance.onWorkflowCompleted()`
 * in [com.lemline.runner.StepByStepRunner]
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ScheduleCleaner : Scheduler() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var scheduleRepository: ScheduleRepository

    override val description: String = "Schedules table cleaning"

    override val schedulable by lazy {
        Cleaner(
            cleanerRepository = scheduleRepository,
            cleanerConfig = lemlineConfig.database().tables().schedules().cleanup()
        )
    }
}
