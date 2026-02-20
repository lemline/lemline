// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection

/**
 * Service for handling schedule-related workflow operations.
 *
 * Provides business logic for:
 * - Scheduling next workflow run after completion (for workflows with schedule.after)
 */
@ApplicationScoped
class ScheduleService {

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    private val logger = logger()

    /**
     * Schedules the next run of a workflow after it completes.
     *
     * This is called when a workflow with `schedule.after` configuration completes.
     * It updates the schedule's outbox_delayed_until to trigger the next run.
     *
     * @param workflowId The workflow ID that completed
     * @param connection Database connection for transaction participation
     * @return true if schedule was updated, false if schedule not found
     */
    suspend fun scheduleAfterCompletion(
        workflowId: WorkflowId,
        connection: Connection
    ): Boolean {
        val schedule = scheduleRepository.findByWorkflowId(workflowId, connection)
            ?: run {
                logger.error { "CRITICAL - Unable to find workflow $workflowId in schedules table" }
                return false
            }

        schedule.scheduleAfterCompletion()
        scheduleRepository.update(schedule, connection)

        logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.outboxDelayedUntil}" }
        return true
    }
}
