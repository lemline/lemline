// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.states.NodeStack
import kotlin.time.Instant

/**
 * Hook interface for workflow lifecycle events.
 *
 * This interface is called by [com.lemline.core.orchestrator.StepByStepOrchestrator] at precise state transition points
 * to emit lifecycle events for external observability. The runner implements this interface
 * to build and emit CloudEvents.
 *
 * **Design Rationale**:
 * - Hooks are placed in the orchestrator (not the command handler) to capture accurate semantics
 * - `task.started` fires when execution actually begins (via `ResumeFromTask`), not when scheduled
 * - `workflow.started` fires only for the first `ResumeFromTask` at the root position
 *
 * **Implementation Notes**:
 * - All methods are `suspend` functions to support async event emission
 * - Implementations should be fire-and-forget (never throw, log warnings on failure)
 * - The `nodeStack` parameter provides all context needed to build CloudEvents
 *
 * @see com.lemline.core.orchestrator.StepByStepOrchestrator for hook call sites
 */
interface LifecycleEventHook {

    // ==========================================
    // Workflow Lifecycle Events
    // ==========================================

    /**
     * Called when a workflow instance is created (before execution starts).
     *
     * `workflow.created` means the initial command has been durably accepted and is eligible
     * for dispatch. For a scheduled workflow it describes one occurrence, not the registration
     * of the schedule. It is not called by the orchestrator: accepting paths persist it with
     * the initial command (see `StepByStepOrchestrator.emitCreationEvents`).
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The initial node stack with workflow context (workflowId, input)
     * @param createdAt The initial command's creation time
     *        ([com.lemline.core.states.WorkflowCommand.ResumeFromTask.createdAt]). It is not
     *        `RootState.startedAt`, which keeps its `$workflow.startedAt` meaning.
     */
    suspend fun onWorkflowCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        createdAt: Instant,
    )

    /**
     * Called when a workflow starts execution.
     *
     * This is triggered on the first `ResumeFromTask` at the root position,
     * indicating the workflow has actually begun execution.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The current node stack with workflow context
     * @param startedAt When the workflow started
     */
    suspend fun onWorkflowStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        startedAt: Instant,
    )

    /**
     * Called when a workflow completes successfully.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The final node stack
     * @param output The workflow output
     * @param completedAt When the workflow completed
     */
    suspend fun onWorkflowCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        output: JsonNode,
        completedAt: Instant,
    )

    /**
     * Called when a workflow fails with an unhandled error.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The node stack at failure point
     * @param error The error that caused the failure
     * @param faultedAt When the failure occurred
     */
    suspend fun onWorkflowFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        error: InternalException.Error,
        faultedAt: Instant,
    )

    // ==========================================
    // Task Lifecycle Events
    // ==========================================

    /**
     * Called when a task is created/scheduled (queued for execution).
     *
     * `task.created` marks the point where the task's command becomes eligible for execution:
     * the transition that produced its `TaskScheduled` event, the acceptance of the initial
     * command for `/do`, or the fork's acceptance for a fork branch. It is emitted once per task
     * invocation: returning to an existing task (after a wait, listener, fork or child workflow)
     * and retry attempts do not emit it. This differs from `onTaskStarted`, which fires when a
     * worker begins processing the task.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The current node stack
     * @param nodePosition The task's position in the workflow tree
     * @param input The task's input data
     * @param createdAt When the task was scheduled
     */
    suspend fun onTaskCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        input: JsonNode,
        createdAt: Instant,
    )

    /**
     * Called when a task starts execution.
     *
     * This is triggered when `ResumeFromTask` begins processing, meaning
     * the task is actually executing (not just scheduled).
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The current node stack
     * @param nodePosition The task's position in the workflow tree
     * @param rawInput The task's input data
     * @param startedAt When the task started
     */
    suspend fun onTaskStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        rawInput: JsonNode,
        startedAt: Instant,
    )

    /**
     * Called when a task completes successfully.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The current node stack
     * @param nodePosition The task's position in the workflow tree
     * @param output The task's output data
     * @param completedAt When the task completed
     */
    suspend fun onTaskCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        output: JsonNode,
        completedAt: Instant,
    )

    /**
     * Called when a task fails with an error.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The node stack at failure point
     * @param nodePosition The task's position in the workflow tree
     * @param error The error that caused the failure
     * @param faultedAt When the failure occurred
     */
    suspend fun onTaskFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        error: InternalException.Error,
        faultedAt: Instant,
    )

    /**
     * Called when a task retry is initiated.
     *
     * @param workflowInfo The workflow definition info (namespace, name, version)
     * @param nodeStack The current node stack
     * @param nodePosition The task's position in the workflow tree
     * @param retryAt When the retry will be attempted
     * @param attemptNumber The retry attempt number (1-based)
     */
    suspend fun onTaskRetried(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        retryAt: Instant,
        attemptNumber: Int,
    )

    companion object {
        /**
         * Default no-op implementation.
         *
         * Use this when lifecycle events are not needed (e.g., in tests).
         */
        val NOOP: LifecycleEventHook = object : LifecycleEventHook {
            override suspend fun onWorkflowCreated(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                createdAt: Instant,
            ) = Unit

            override suspend fun onWorkflowStarted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                startedAt: Instant
            ) = Unit

            override suspend fun onWorkflowCompleted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                output: JsonNode,
                completedAt: Instant
            ) = Unit

            override suspend fun onWorkflowFaulted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                error: InternalException.Error,
                faultedAt: Instant
            ) = Unit

            override suspend fun onTaskCreated(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                nodePosition: NodePosition,
                input: JsonNode,
                createdAt: Instant
            ) = Unit

            override suspend fun onTaskStarted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                nodePosition: NodePosition,
                rawInput: JsonNode,
                startedAt: Instant
            ) = Unit

            override suspend fun onTaskCompleted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                nodePosition: NodePosition,
                output: JsonNode,
                completedAt: Instant
            ) = Unit

            override suspend fun onTaskFaulted(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                nodePosition: NodePosition,
                error: InternalException.Error,
                faultedAt: Instant
            ) = Unit

            override suspend fun onTaskRetried(
                workflowInfo: WorkflowInfo,
                nodeStack: NodeStack,
                nodePosition: NodePosition,
                retryAt: Instant,
                attemptNumber: Int
            ) = Unit
        }
    }
}
