// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.StepByStepOrchestrator.completeTask
import com.lemline.core.orchestrator.StepByStepOrchestrator.processInternalWorkflowException
import com.lemline.core.orchestrator.StepByStepOrchestrator.tryCatch
import com.lemline.core.processors.TryProcessor
import com.lemline.core.processors.scope.withTask
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.EmitStarted
import com.lemline.core.states.WorkflowEvent.ForkBranchFailed
import com.lemline.core.states.WorkflowEvent.ForkStarted
import com.lemline.core.states.WorkflowEvent.ListenForEachCompleted
import com.lemline.core.states.WorkflowEvent.ListenStarted
import com.lemline.core.states.WorkflowEvent.Outcome
import com.lemline.core.states.WorkflowEvent.RunWorkflowStarted
import com.lemline.core.states.WorkflowEvent.TaskRetryScheduled
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.states.WorkflowEvent.WaitStarted
import com.lemline.core.states.WorkflowEvent.WorkflowCompleted
import com.lemline.core.states.WorkflowEvent.WorkflowFailed
import com.lemline.core.tasks.toJava
import com.lemline.core.tasks.toKotlin
import com.lemline.core.workflows.getNode
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * The StepByStepOrchestrator object handles the orchestration of workflow execution,
 * managing task states, and processing commands to resume or complete various
 * tasks and activities within a workflow context.
 *
 * This orchestrator provides methods to initiate a workflow, execute commands at both
 * task and activity levels, and manage task transitions during workflow execution.
 * Internally, it handles resuming workflows that involve both successful task completions
 * and error states.
 */
@ExperimentalTime
object StepByStepOrchestrator {

    /**
     * Provides a `WorkflowCommand` starting a workflow.
     */
    fun initCmd(
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonElement = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
    ): WorkflowCommand.ResumeFromTask {
        val rootState = RootState(
            startedAt = startedAt,
            workflowId = workflowId,
            workflowInput = workflowInput,
            hasWaitingParent = hasWaitingParent,
        )
        val nodeStack = NodeStack(listOf(NodePosition.root to rootState))

        return WorkflowCommand.ResumeFromTask(
            nodePosition = NodePosition.doRoot,
            rawInput = workflowInput,
            nodeStack = nodeStack,
            flowDirective = null,
        )
    }

    /**
     * Processes a given workflow command and resumes workflow execution based on the command's type.
     *
     * @param workflow The workflow definition
     * @param command The command to execute
     * @param workflowInfo The workflow identity (namespace, name, version)
     * @param lifecycleHook Optional hook for lifecycle event callbacks (default: no-op)
     */
    suspend fun runByTask(
        workflow: Workflow,
        command: WorkflowCommand,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        val node = workflow.getNode(command.nodePosition)

        val event = when (command) {
            is WorkflowCommand.ResumeFromTask -> resumeFromTask(
                nodeStack = command.nodeStack,
                node = node,
                rawInput = command.rawInput,
                flowDirective = command.flowDirective?.toJava(),
                workflowInfo = workflowInfo,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowCommand.ResumeWithCompletedTask -> resumeFromCompletedTask(
                nodeStack = command.nodeStack,
                node = node,
                rawOutput = command.rawOutput,
                workflowInfo = workflowInfo,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowCommand.ResumeWithFailedTask -> resumeFromFailedTask(
                nodeStack = command.nodeStack,
                node = node,
                error = command.error,
                workflowInfo = workflowInfo,
                lifecycleHook = lifecycleHook,
            )
        }

        emitLifecycleEventsForEvent(event, workflowInfo, lifecycleHook)

        return event
    }

    /**
     * Emits lifecycle events based on the workflow event type.
     *
     * This handles workflow-level events and task scheduling. Task completion/failure
     * events are emitted closer to where they occur:
     * - `task.completed` in [completeTask] for async completions, [emitTaskExitEvents] for sync
     * - `task.faulted` in [tryCatch] when errors are caught
     * - `task.retried` in [processInternalWorkflowException] when retry is scheduled
     *
     * Event mapping:
     * - [WorkflowCompleted] → workflow.completed
     * - [WorkflowFailed] → workflow.faulted
     * - [TaskScheduled] → task.created (only when scheduling a child, not returning to parent)
     */
    private suspend fun emitLifecycleEventsForEvent(
        event: WorkflowEvent,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ) {
        when (event) {
            is WorkflowCompleted -> {
                lifecycleHook.onWorkflowCompleted(
                    workflowInfo = workflowInfo,
                    nodeStack = event.nodeStack,
                    output = event.output,
                    completedAt = event.completedAt,
                )
            }

            is WorkflowFailed -> {
                lifecycleHook.onWorkflowFaulted(
                    workflowInfo = workflowInfo,
                    nodeStack = event.nodeStack,
                    error = event.error,
                    failedAt = event.failedAt,
                )
            }

            is TaskScheduled -> {
                // Emit task.created only when scheduling a new task
                if (event.isNew) {
                    lifecycleHook.onTaskCreated(
                        workflowInfo = workflowInfo,
                        nodeStack = event.nodeStack,
                        nodePosition = event.nodePosition,
                        input = event.rawInput,
                        createdAt = Clock.System.now()
                    )
                }
            }

            else -> { /* No lifecycle event for other event types */
            }
        }
    }

    /**
     * Executes the workflow based on the provided command, processing activity nodes recursively
     * until an appropriate event is returned.
     *
     * @param workflow The workflow definition
     * @param command The command to execute
     * @param workflowInfo The workflow identity (namespace, name, version)
     * @param lifecycleHook Optional hook for lifecycle event callbacks (default: no-op)
     */
    suspend fun runByActivity(
        workflow: Workflow,
        command: WorkflowCommand,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {

        return when (val event = runByTask(workflow, command, workflowInfo, lifecycleHook)) {
            is TaskScheduled -> if (workflow.getNode(event.nodePosition).isActivity()) {
                event
            } else {
                runByActivity(workflow, event.resume(), workflowInfo, lifecycleHook)
            }

            is ActivityStarted -> event
            is EmitStarted -> event
            is WaitStarted -> event
            is ListenStarted -> event
            is ListenForEachCompleted -> event
            is TaskRetryScheduled -> event
            is RunWorkflowStarted -> event
            is ForkStarted -> event
            is Outcome -> event
        }
    }

    /**
     * Resumes the workflow execution from a specific task.
     *
     * Emits task.started lifecycle event when a task begins execution.
     */
    internal suspend fun resumeFromTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {

        // Increment workflow step counter
        val updatedStateStack = nodeStack.incrementStep()

        return try {
            // run the next task within a try-catch block to handle workflow-caught exceptions
            tryCatch(node, updatedStateStack, workflowInfo, lifecycleHook) {
                startTask(updatedStateStack, node, rawInput, flowDirective, workflowInfo, lifecycleHook)
            }
        } catch (e: Exception) {
            // Uncaught failure within a fork branch
            forkBranchFailed(updatedStateStack, node, e)
            // Uncaught failure
                ?: WorkflowFailed(
                    nodeStack = updatedStateStack,
                    nodePosition = node.position,
                    rawInput = rawInput,
                    rawOutput = null,
                    flowDirective = flowDirective?.toKotlin(),
                    exception = e,
                    failedAt = Clock.System.now(),
                )
        }
    }

    /**
     * Resumes the workflow execution from an asynchronously completed task,
     * (transitioning the current task from an incomplete state to the next expected state.)
     *
     * Emits task.completed lifecycle event when task finishes successfully.
     */
    internal suspend fun resumeFromCompletedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonElement,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {

        return try {
            // Resume the completed task (transforms output, updates state)
            tryCatch(node, nodeStack, workflowInfo, lifecycleHook) {
                completeTask(nodeStack, node, rawOutput, workflowInfo, lifecycleHook)
            }
        } catch (e: Exception) {
            // Uncaught failure within a fork branch
            forkBranchFailed(nodeStack, node, e)
            // Uncaught failure
                ?: WorkflowFailed(
                    nodeStack = nodeStack,
                    nodePosition = node.position,
                    rawInput = null,
                    rawOutput = rawOutput,
                    flowDirective = null,
                    exception = e
                )
        }
    }

    /**
     * Resumes the workflow execution from an asynchronously failed task,
     * (hopefully catching the error, if not returns a ForkBranchFailed or TaskFailed event on the same node)
     *
     * The task.faulted event is emitted in [tryCatch] where the error is caught.
     */
    internal suspend fun resumeFromFailedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        error: InternalException.Error,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        return try {
            // Resume the failed task (hopefully, the error can be handled)
            // task.faulted is emitted in tryCatch when the exception is caught
            tryCatch(node, nodeStack, workflowInfo, lifecycleHook) {
                throw InternalException(error)
            }
        } catch (e: Exception) {
            // Uncaught failure within a fork branch
            forkBranchFailed(nodeStack, node, e)?.let { return it }
            // Uncaught failure
            WorkflowFailed(
                nodeStack = nodeStack,
                nodePosition = node.position,
                rawInput = null,
                rawOutput = null,
                flowDirective = null,
                exception = e
            )
        }
    }

    /**
     * Executes a given block of code within a try-catch construct.
     * If the block execution succeeds, the result is returned.
     * If it fails with an `InternalWorkflowException`, the workflow is trying to resume through a parent `try` node.
     *
     * Emits `task.faulted` when an error is caught (regardless of whether it's handled or re-thrown).
     */
    suspend fun tryCatch(
        current: Node<*>,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        block: suspend () -> WorkflowEvent
    ): WorkflowEvent = try {
        block()
    } catch (e: InternalException) {
        // Emit task.faulted - the task failed with an error
        lifecycleHook.onTaskFaulted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = NodePosition(e.error.position),
            error = e.error,
            failedAt = Clock.System.now(),
        )
        processInternalWorkflowException(e, current, nodeStack, workflowInfo, lifecycleHook)
    }

    /**
     * Handle exception by finding a TryTask and returning the appropriate state transition.
     *
     * Walks up the node tree looking for a TryTask that can handle the error.
     * Stops at error boundaries:
     * - Fork tasks act as error boundaries and handle errors internally
     * - Listen tasks with foreach act as error boundaries for foreach iterations
     *
     * Emits `task.retried` when a retry is scheduled.
     */
    private suspend fun processInternalWorkflowException(
        exception: InternalException,
        failingNode: Node<*>,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        // Find the nearest TryTask - within the same error boundary - that can handle this error
        var current: Node<*>? = failingNode

        do {
            // if we reached a try task, we check if this error can be handled by it
            // if yes, we continue from there (retry or catch)
            if (current!!.task is TryTask) {
                // current state of the try node
                val tryState = nodeStack[current.position] as TryState
                val processor = current.processor as TryProcessor
                // check that this node actually can handle this error
                if (processor.isCatching(exception.error, tryState, nodeStack.stateScope)) {
                    val event = processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryState,
                        nodeStack = nodeStack
                    )
                    // Emit task.retried if a retry was scheduled
                    if (event is TaskRetryScheduled) {
                        lifecycleHook.onTaskRetried(
                            workflowInfo = workflowInfo,
                            nodeStack = event.nodeStack,
                            nodePosition = event.nodePosition,
                            retryAt = event.retryAt,
                            attemptNumber = tryState.attemptIndex + 1,
                        )
                    }
                    return event
                }
            }
            current = current.parent
            // Stop at error boundaries: fork tasks and listen tasks with foreach
        } while (current != null && !isErrorBoundary(current))

        // No handler found - fail workflow
        throw exception
    }

    /**
     * Check if a node is an error boundary.
     * Error boundaries prevent errors from propagating beyond them:
     * - ForkTask: errors in branches are handled by the fork
     * - ListenTask with foreach: errors in foreach.do are handled by the listen handler
     */
    private fun isErrorBoundary(node: Node<*>): Boolean = when (val task = node.task) {
        is ForkTask -> true
        is ListenTask -> task.foreach != null
        else -> false
    }

    /**
     * Execute a single step of workflow execution.
     *
     * Determines whether we enter the node for the first time (stack top is parent)
     * or re-enter after child completion (stack top is this node).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun startTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective?,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        val isFirstEntry = nodeStack[node.position] == null

        // Emit entry events (workflow.started, task.created, task.started)
        if (isFirstEntry) {
            emitTaskEntryEvents(nodeStack, node, rawInput, workflowInfo, lifecycleHook)
        }

        // Execute the task
        val event = if (isFirstEntry) {
            node.processor.enterFromParent(nodeStack, workflowInfo, rawInput, lifecycleHook)
        } else {
            node.processor.reEnterFromChild(nodeStack, workflowInfo, rawInput, flowDirective, lifecycleHook)
        }

        return event
    }

    /**
     * Emits lifecycle events when entering a task for the first time.
     *
     * Events emitted:
     * - `workflow.started` when entering `/do` (first task after root)
     * - `task.created` for `/do` (since it's not scheduled via TaskScheduled)
     * - `task.started` for all tasks
     */
    private suspend fun emitTaskEntryEvents(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonElement,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ) {
        val now = Clock.System.now()

        // Emit workflow.started when entering /do (first task after root)
        if (node.position == NodePosition.doRoot) {
            lifecycleHook.onWorkflowStarted(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                startedAt = nodeStack.rootState.startedAt,
            )
            // Also emit task.created for /do since it's the first task
            // (not scheduled via TaskScheduled event)
            lifecycleHook.onTaskCreated(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                nodePosition = node.position,
                input = rawInput,
                createdAt = now,
            )
        }

        // Emit task.started for all nodes (including /do)
        lifecycleHook.onTaskStarted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = node.position,
            rawInput = rawInput,
            startedAt = now,
        )
    }

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     *
     * Emits `task.completed` after the task completes, with the transformed output.
     */
    internal suspend fun completeTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonElement,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        val scope = nodeStack.stateScope.withTask(node, nodeStack.currentState.startedAt)

        val event = node.processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = node.processor.getFlowDirective(),
            currentScope = scope,
            nodeStack = nodeStack,
            workflowInfo = workflowInfo,
            lifecycleHook = lifecycleHook
        )

        return event
    }

    /**
     * Handles the failure of a branch execution stemming from a Fork task in a workflow (if any)
     */
    private fun forkBranchFailed(
        nodeStack: NodeStack,
        failingNode: Node<*>,
        exception: Exception
    ): ForkBranchFailed? {
        // Find the nearest Fork task up the current node
        var current: Node<*> = failingNode

        while (current.parent != null) {
            val forkNode = current.parent

            if (forkNode.task is ForkTask) {
                // Pop all states up to and including the fork node
                return ForkBranchFailed(
                    nodeStack = nodeStack.popUntil(forkNode.position),
                    branchName = current.name,
                    error = InternalException.Error.from(exception, failingNode.position),
                    failedAt = Clock.System.now(),
                )
            }
            current = current.parent
        }
        return null
    }
}
