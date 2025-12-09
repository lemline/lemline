// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.processors.TryProcessor
import com.lemline.core.processors.scope.withTask
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ActivityStarted
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
import com.lemline.core.workflows.toJava
import com.lemline.core.workflows.toKotlin
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForkTask
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
        lifecycleHook: LifecycleEventHook = LifecycleEventHook.NOOP,
    ): WorkflowEvent {
        val node = workflow.getNode(command.nodePosition)
        val rootState = command.nodeStack[NodePosition.root] as RootState

        // Check if this is the first task (workflow starting)
        val isWorkflowStart = command is WorkflowCommand.ResumeFromTask &&
            command.nodePosition == NodePosition.doRoot &&
            rootState.workflowStep == 0

        // Emit workflow.started if this is the first task
        if (isWorkflowStart) {
            lifecycleHook.onWorkflowStarted(
                workflowInfo = workflowInfo,
                nodeStack = command.nodeStack,
                startedAt = rootState.startedAt,
            )
        }

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

        // Emit lifecycle events based on event type
        when (event) {
            is WorkflowCompleted -> lifecycleHook.onWorkflowCompleted(
                workflowInfo = workflowInfo,
                nodeStack = event.nodeStack,
                output = event.output,
                completedAt = event.completedAt,
            )

            is WorkflowFailed -> lifecycleHook.onWorkflowFaulted(
                workflowInfo = workflowInfo,
                nodeStack = event.nodeStack,
                error = event.error,
                failedAt = event.failedAt,
            )

            is TaskScheduled -> lifecycleHook.onTaskCreated(
                workflowInfo = workflowInfo,
                nodeStack = event.nodeStack,
                nodePosition = event.nodePosition,
                rawInput = event.rawInput,
                createdAt = Clock.System.now(),
            )

            is TaskRetryScheduled -> {
                // Find the TryState in the nodeStack to get the attempt number
                // The TryState is stored at the try node position
                val attemptNumber = event.nodeStack.map { entry ->
                    (entry.value as? TryState)?.attemptIndex
                }.filterNotNull().maxOrNull() ?: 1
                lifecycleHook.onTaskRetried(
                    workflowInfo = workflowInfo,
                    nodeStack = event.nodeStack,
                    nodePosition = event.nodePosition,
                    retryAt = event.retryAt,
                    attemptNumber = attemptNumber,
                )
            }

            else -> { /* No lifecycle event for other event types */
            }
        }

        return event
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
        lifecycleHook: LifecycleEventHook = LifecycleEventHook.NOOP,
    ): WorkflowEvent {

        return when (val event = runByTask(workflow, command, workflowInfo, lifecycleHook)) {
            is TaskScheduled -> if (workflow.getNode(event.nodePosition).isActivity()) {
                event
            } else {
                runByActivity(workflow, event.resume(), workflowInfo, lifecycleHook)
            }

            is ActivityStarted -> event
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
        lifecycleHook: LifecycleEventHook = LifecycleEventHook.NOOP,
    ): WorkflowEvent {
        // Increment workflow step counter
        val updatedStateStack = nodeStack.incrementStep()
        val now = Clock.System.now()

        // Emit task.started - task is now actually executing
        lifecycleHook.onTaskStarted(
            workflowInfo = workflowInfo,
            nodeStack = updatedStateStack,
            nodePosition = node.position,
            rawInput = rawInput,
            startedAt = now,
        )

        return try {
            // run the next task within a try-catch block to handle workflow-caught exceptions
            tryCatch(node, updatedStateStack) {
                startTask(updatedStateStack, node, rawInput, flowDirective)
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
        lifecycleHook: LifecycleEventHook = LifecycleEventHook.NOOP,
    ): WorkflowEvent {
        // Emit task.completed - task finished successfully
        lifecycleHook.onTaskCompleted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = node.position,
            rawOutput = rawOutput,
            completedAt = Clock.System.now(),
        )

        return try {
            // Resume the completed task (transforms output, updates state)
            tryCatch(node, nodeStack) {
                completeTask(nodeStack, node, rawOutput)
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
     * Emits task.faulted lifecycle event when task fails with an error.
     */
    internal suspend fun resumeFromFailedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        error: InternalException.Error,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook = LifecycleEventHook.NOOP,
    ): WorkflowEvent {
        // Emit task.faulted - task failed with an error
        lifecycleHook.onTaskFaulted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = node.position,
            error = error,
            failedAt = Clock.System.now(),
        )

        return try {
            // Resume the failed task (hopefully, the error can be handled)
            tryCatch(node, nodeStack) {
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
     */
    suspend fun tryCatch(
        current: Node<*>,
        nodeStack: NodeStack,
        block: suspend () -> WorkflowEvent
    ): WorkflowEvent = try {
        block()
    } catch (e: InternalException) {
        processInternalWorkflowException(e, current, nodeStack)
    }

    /**
     * Handle exception by finding a TryTask and returning the appropriate state transition.
     *
     * Walks up the node tree looking for a TryTask that can handle the error.
     * Stops at fork boundaries - fork tasks act as error boundaries and handle
     * errors internally, according to their compete strategy.
     */
    private fun processInternalWorkflowException(
        exception: InternalException,
        failingNode: Node<*>,
        nodeStack: NodeStack,
    ): WorkflowEvent {
        // Find the nearest TryTask - within the same fork branch - that can handle this error
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
                    return processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryState,
                        nodeStack = nodeStack
                    )
                }
            }
            current = current.parent
        } while (current != null && current.task !is ForkTask)

        // No handler found - fail workflow
        throw exception
    }

    /**
     * Execute a single step of workflow execution - pure function.
     *
     * Determines whether we enter the node for the first time (stack top is parent)
     * or re-enter after child completion (stack top is this node).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun startTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective?,
    ): WorkflowEvent {
        // Do we have a state for this node?
        return if (nodeStack.lastPosition == node.position) {
            // Re-entering after a child completed
            node.processor.enterFromChild(nodeStack, rawInput, flowDirective)
        } else {
            // First time entering this node
            node.processor.enterFromParent(nodeStack, rawInput)
        }
    }

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     */
    internal fun completeTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonElement
    ): WorkflowEvent {
        val scope = nodeStack.stateScope.withTask(node, nodeStack.lastState.startedAt)

        return node.processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = node.processor.getFlowDirective(),
            currentScope = scope,
            nodeStack = nodeStack
        )
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
