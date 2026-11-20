// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.starters

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import java.time.ZoneId
import kotlin.time.Instant

/**
 * A prepared workflow ready to be dispatched.
 *
 * Contains all components needed to start a workflow instance:
 * - The instance message to send to the workflow channel
 * - Optional schedule model for scheduled workflows
 *
 * The caller accepts the instance: it persists the command (or sends it) and, in the same
 * transaction, enqueues its creation events with
 * [com.lemline.runner.common.lifecycle.CreationEventEnqueuer.enqueueCreation]. It should first
 * stamp the command with its acceptance time ([withCreatedAt]); preparation time is only a default.
 *
 * @property instanceMessage The instance message to send (null for cron-scheduled workflows)
 * @property scheduleModel The schedule model for scheduled workflows (null if no schedule)
 */
data class PreparedWorkflow<S>(
    val instanceMessage: InstanceMessage<WorkflowCommand.ResumeFromTask>?,
    val scheduleModel: S?,
) {
    /** The instance message stamped with [createdAt], its acceptance time. */
    fun withCreatedAt(createdAt: Instant): PreparedWorkflow<S> = copy(
        instanceMessage = instanceMessage?.let { it.copy(workflowState = it.workflowState.copy(createdAt = createdAt)) },
    )
}

/**
 * Interface for preparing workflows for execution.
 *
 * Implementations are responsible for:
 * - Loading workflow definitions
 * - Validating input against workflow schema
 * - Creating instance messages and schedule models
 */
interface WorkflowStarter<S> {
    /**
     * Prepares a workflow for execution.
     *
     * Depending on the schedule of the workflow, different messages are needed:
     * - no schedule -> a single instanceMessage
     * - schedule after or every -> an instanceMessage and a scheduleModel
     * - schedule cron -> a single scheduleModel
     *
     * @param workflowId The unique ID for this workflow instance
     * @param workflowNamespace The namespace of the workflow
     * @param workflowName The name of the workflow
     * @param optionalVersion Optional specific version (null for latest)
     * @param workflowInput The input data for the workflow
     * @param hasWaitingParent Whether this workflow has a parent waiting for completion
     * @param zoneId Optional timezone for scheduling
     * @param onError Callback for error handling
     * @return A prepared workflow ready to be dispatched
     */
    suspend fun prepareWorkflow(
        workflowId: WorkflowId,
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonNode,
        hasWaitingParent: Boolean,
        zoneId: ZoneId?,
        onError: (String) -> Nothing,
    ): PreparedWorkflow<S>
}
