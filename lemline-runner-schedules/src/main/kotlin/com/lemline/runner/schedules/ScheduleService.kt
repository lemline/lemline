// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock

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
     * Inserts one successor and releases the completed occurrence for cleanup in the caller's transaction.
     *
     * @param workflowId The workflow ID that completed
     * @param connection Database connection for transaction participation
     * @return true if schedule was updated, false if schedule not found
     */
    suspend fun scheduleAfterCompletion(
        workflowId: WorkflowId,
        connection: Connection
    ): Boolean {
        val schedule = scheduleRepository.findByWorkflowIdForUpdate(workflowId, connection)
            ?: run {
                logger.debug { "No retained schedule occurrence for workflow $workflowId" }
                return false
            }

        // cleanupAfter is set only once this occurrence has handed off to its successor.
        // Keep this guard even if the successor has already been processed and cleaned up.
        if (schedule.scheduleAfter == null || schedule.cleanupAfter != null) return false

        val completedAt = Clock.System.now()
        val next = schedule.nextAfterCompletion(completedAt)
        scheduleRepository.insert(next, connection)
        schedule.cleanupAfter = completedAt
        scheduleRepository.update(schedule, connection)

        logger.debug { "Scheduled workflow ${schedule.workflowName} for ${next.outboxScheduledFor}" }
        return true
    }
}
