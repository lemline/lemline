// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.AsyncTaskException
import com.lemline.core.errors.AsyncTaskException.ForkStartedException
import com.lemline.core.errors.AsyncTaskException.RunWorkflowStartedException
import com.lemline.core.errors.AsyncTaskException.WaitStartedException
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.orchestrator.context.merge
import com.lemline.core.processors.TryProcessor
import com.lemline.core.states.ForkState
import com.lemline.core.states.RootState
import com.lemline.core.states.TaskStates
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.BranchCompleted
import com.lemline.core.states.WorkflowEvent.BranchFailed
import com.lemline.core.states.WorkflowEvent.ForkStarted
import com.lemline.core.states.WorkflowEvent.RetryScheduled
import com.lemline.core.states.WorkflowEvent.RunWorkflowStarted
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.states.WorkflowEvent.WaitStarted
import com.lemline.core.states.WorkflowEvent.WorkflowCompleted
import com.lemline.core.states.WorkflowEvent.WorkflowFailed
import com.lemline.core.states.updateWith
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

    private val logger = logger()

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
        val taskStates: TaskStates = mapOf(NodePosition.root to rootState)

        return WorkflowCommand.ResumeFromTask(
            nodePosition = NodePosition.doRoot,
            rawInput = workflowInput,
            taskStates = taskStates,
            flowDirective = null,
        )
    }

    /**
     * Processes a given workflow command and resumes workflow execution based on the command's type.
     */
    suspend fun runByTask(
        workflow: Workflow,
        command: WorkflowCommand,
    ): WorkflowEvent {
        val node = workflow.getNode(command.nodePosition)

        return when (command) {
            is WorkflowCommand.ResumeFromTask -> resumeFromTask(
                taskStates = command.taskStates,
                node = node,
                rawInput = command.rawInput,
                flowDirective = command.flowDirective?.toJava(),
            )

            is WorkflowCommand.ResumeWithCompletedTask -> resumeFromCompletedTask(
                taskStates = command.taskStates,
                node = node,
                rawOutput = command.rawOutput,
            )

            is WorkflowCommand.ResumeWithFailedTask -> resumeFromFailedTask(
                taskStates = command.taskStates,
                node = node,
                error = command.error,
            )
        }
    }

    /**
     * Executes the workflow based on the provided command, processing activity nodes recursively
     * until an appropriate event is returned.
     */
    suspend fun runByActivity(
        workflow: Workflow,
        command: WorkflowCommand,
    ): WorkflowEvent {

        return when (val event = runByTask(workflow, command)) {
            is TaskScheduled -> if (workflow.getNode(event.nodePosition).isActivity()) {
                event
            } else {
                runByActivity(workflow, event.resume())
            }

            is WaitStarted -> event
            is RetryScheduled -> event
            is RunWorkflowStarted -> event
            is ForkStarted -> event
            is WorkflowEvent.Outcome -> event
        }
    }

    /**
     * Resumes the workflow execution from a specific task.
     */
    internal suspend fun resumeFromTask(
        taskStates: TaskStates,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
    ): WorkflowEvent {
        // Increment workflow step counter
        val rootState = taskStates[NodePosition.root] as RootState
        val updatedTaskStates = taskStates + (NodePosition.root to rootState.copy(
            workflowStep = rootState.workflowStep + 1
        ))

        try {
            val stepResult: StepResult = try {
                // run the next task within a try-catch block to handle workflow-caught exceptions
                tryCatch(node, updatedTaskStates) {
                    startTask(updatedTaskStates, node, rawInput, flowDirective)
                }
            } catch (e: AsyncTaskException) {
                // Update states of the current node, even if not completed yet
                val states = updatedTaskStates.updateWith(mapOf(node.position to e.state), null)
                // Return immediately with an event
                return when (e) {
                    is RunWorkflowStartedException -> RunWorkflowStarted(
                        taskStates = states,
                        nodePosition = node.position,
                        runState = e.state,
                        rawInput = e.transformedInput,
                        childConfig = e.config,
                    )

                    is WaitStartedException -> WaitStarted(
                        taskStates = states,
                        nodePosition = node.position,
                        waitState = e.state,
                        rawOutput = e.transformedInput,
                        waitUntil = e.config.waitUntil,
                    )

                    is ForkStartedException -> ForkStarted(
                        taskStates = states,
                        nodePosition = node.position,
                        forkState = e.state,
                        rawInput = e.transformedInput,
                    )
                }
            }

            return getNextEvent(updatedTaskStates, stepResult, node)
        } catch (e: Exception) {
            // Are we within a fork?
            forkBranchFailed(updatedTaskStates, node, e)?.let { return it }
            // Uncaught task failure
            return WorkflowFailed(
                taskStates = updatedTaskStates.toMap(),
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
     */
    internal suspend fun resumeFromCompletedTask(
        taskStates: TaskStates,
        node: Node<*>,
        rawOutput: JsonElement,
    ): WorkflowEvent = try {
        logger.debug { "resumeFromCompletedTask  In: node=${node.position}, output=$rawOutput, states=$taskStates" }

        // Resume the completed task (transforms output, updates state)
        val stepResult = tryCatch(node, taskStates) {
            completeTask(taskStates, node, rawOutput)
        }

        getNextEvent(taskStates, stepResult, node)
    } catch (e: Exception) {
        // if we are within a fork
        forkBranchFailed(taskStates, node, e)?.let { return it }
        // Uncaught task failure
        WorkflowFailed(
            taskStates = taskStates,
            nodePosition = node.position,
            rawInput = null,
            rawOutput = rawOutput,
            flowDirective = null,
            exception = e
        )
    }.also {
        logger.debug { "resumeFromCompletedTask Out: event=$it" }
    }

    /**
     * Resumes the workflow execution from an asynchronously failed task,
     * (hopefully catching the error, if not returns a ForkBranchFailed or TaskFailed event on the same node)
     */
    internal suspend fun resumeFromFailedTask(
        taskStates: TaskStates,
        node: Node<*>,
        error: InternalException.Error,
    ): WorkflowEvent = try {
        logger.debug { "resumeFromFailedTask  In: node=${node.position}, error=$error, states=$taskStates" }

        // Resume the failed task (hopefully, the error can be handled)
        val stepResult = tryCatch(node, taskStates) {
            throw InternalException(error)
        }

        getNextEvent(taskStates, stepResult, node)
    } catch (e: Exception) {
        // if we are within a fork
        forkBranchFailed(taskStates, node, e)?.let { return it }
        // Uncaught task failure
        WorkflowFailed(
            taskStates = taskStates,
            nodePosition = node.position,
            rawInput = null,
            rawOutput = null,
            flowDirective = null,
            exception = e
        )
    }.also {
        logger.debug { "resumeFromFailedTask Out: event=$it" }
    }

    private fun getNextEvent(
        taskStates: TaskStates,
        result: StepResult,
        node: Node<*>
    ): WorkflowEvent {
        // Update states
        val newStates = taskStates.updateWith(result.stateUpdates, result.nextContext)

        // Workflow completed
        if (result.nextNode == null) {
            logger.debug { "Workflow completed with output: ${result.nextInput}" }
            return WorkflowCompleted(
                output = result.nextInput,
                completedAt = Clock.System.now(),
                taskStates = newStates,
            )
        }

        // The current task must be retried
        if (result.retryAt != null) return RetryScheduled(
            taskStates = newStates,
            nodePosition = result.nextNode.position,
            rawInput = result.nextInput,
            flowDirective = result.nextDirective?.toKotlin(),
            retryAt = result.retryAt
        )

        // Are we now completing a fork branch?
        forkBranchCompleted(newStates, result, node)?.let { return it }

        // Next Task
        return TaskScheduled(
            taskStates = newStates,
            nodePosition = result.nextNode.position,
            rawInput = result.nextInput,
            flowDirective = result.nextDirective?.toKotlin(),
        )
    }

    private fun forkBranchFailed(
        taskStates: TaskStates,
        failingNode: Node<*>,
        exception: Exception
    ): BranchFailed? {
        // Find the nearest Fork task up the current node
        var current: Node<*> = failingNode

        while (current.parent != null) {
            val forkNode = current.parent

            if (forkNode.task is ForkTask) {
                // Clean state of all nodes up to the fork node
                val cleanUpdateStates = buildMap {
                    var node = failingNode
                    do {
                        put(node.position, null)
                        node = node.parent!!
                    } while (node != forkNode)
                }
                //
                return BranchFailed(
                    taskStates = taskStates.updateWith(cleanUpdateStates, null),
                    nodePosition = forkNode.position,
                    branchName = current.name,
                    error = InternalException.Error.from(exception, failingNode.position),
                    failedAt = Clock.System.now(),
                )
            }
            current = current.parent
        }
        return null
    }

    private fun forkBranchCompleted(
        taskStates: TaskStates,
        stepResult: StepResult,
        node: Node<*>
    ): BranchCompleted? {
        val nextNode = stepResult.nextNode!!
        val nextState = taskStates[nextNode.position]
        // Is NextNode a fork? Do we enter from Child?
        if (nextNode.task is ForkTask && nextState != null && nextState is ForkState) {
            // Find the branch name
            val branchName = nextNode.children?.find { it.name == node.name }?.name
                ?: throw IllegalStateException("Fork - can not find ${node.name} in ${nextNode.children?.joinToString { it.name }}")

            return BranchCompleted(
                taskStates = taskStates,
                nodePosition = nextNode.position,
                branchName = branchName,
                output = stepResult.nextInput,
                completedAt = Clock.System.now(),
                flowDirective = stepResult.nextDirective?.toKotlin(),
            )
        }

        return null
    }

    /**
     * Executes a given block of code within a try-catch construct.
     * If the block execution succeeds, the result is returned.
     * If it fails with an `InternalWorkflowException`, the workflow is trying to resume through a parent `try` node.
     */
    suspend fun tryCatch(
        current: Node<*>,
        taskStates: TaskStates,
        block: suspend () -> StepResult
    ): StepResult = try {
        block()
    } catch (e: InternalException) {
        processInternalWorkflowException(e, current, taskStates)
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
        taskStates: TaskStates,
    ): StepResult {
        // Find the nearest TryTask - within the same fork branch - that can handle this error
        var current: Node<*>? = failingNode

        do {
            // if we reached a try task, we check if this error can be handled by it
            // if yes, we continue from there (retry or catch)
            if (current!!.task is TryTask) {
                @Suppress("UNCHECKED_CAST")
                current as Node<TryTask>
                // current scope of the try node
                val tryScope = getScope(current, taskStates)
                // current state of the try node
                val tryState = taskStates[current.position] as TryState
                // processor for the try node
                val processor = TryProcessor(current)
                // check that this node actually can handle this error
                if (processor.isCatching(exception.error, tryState, tryScope)) {
                    return processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryState,
                        scope = tryScope
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
     * Determines whether we enter the node for the first time (no state)
     * or re-enter after child completion (state exists).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun startTask(
        taskStates: TaskStates,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective?,
    ): StepResult {
        val state = taskStates[node.position]
        val scope = getScope(node, taskStates)
        val processor = ProcessorFactory.getProcessor(node)

        return if (state == null) {
            logger.debug { "Entering Down  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput)" }
            // First time entering this node
            processor.enterFromParent(rawInput, scope)
        } else {
            logger.debug {
                "ReEntering Up  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput${
                    flowDirective?.get()?.let { ", flow=$it" } ?: ""
                }), state=$state"
            }
            // Re-entering after a child completed
            processor.enterFromChild(state, flowDirective, rawInput, scope)
        }
    }

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     */
    internal fun completeTask(
        taskStates: TaskStates,
        node: Node<*>,
        rawOutput: JsonElement
    ): StepResult {
        val processor = ProcessorFactory.getProcessor(node)
        val scope = getScope(node, taskStates)

        return processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = processor.getFlowDirective(),
            parentScope = scope,
            taskScope = null
        )
    }

    /**
     * Retrieves the `Scope` associated with the given node by combining its own expression arguments
     * with those of its parent nodes in the tree, if present.
     */
    private fun getScope(current: Node<*>, taskStates: TaskStates): Scope =
        (taskStates[current.position]?.scope ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getScope(it, taskStates) })
}
