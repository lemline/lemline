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
import com.lemline.core.processors.TryProcessor
import com.lemline.core.states.ForkState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ForkBranchCompleted
import com.lemline.core.states.WorkflowEvent.ForkBranchFailed
import com.lemline.core.states.WorkflowEvent.ForkStarted
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
     */
    suspend fun runByTask(
        workflow: Workflow,
        command: WorkflowCommand,
    ): WorkflowEvent {
        val node = workflow.getNode(command.nodePosition)

        return when (command) {
            is WorkflowCommand.ResumeFromTask -> resumeFromTask(
                nodeStack = command.nodeStack,
                node = node,
                rawInput = command.rawInput,
                flowDirective = command.flowDirective?.toJava(),
            )

            is WorkflowCommand.ResumeWithCompletedTask -> resumeFromCompletedTask(
                nodeStack = command.nodeStack,
                node = node,
                rawOutput = command.rawOutput,
            )

            is WorkflowCommand.ResumeWithFailedTask -> resumeFromFailedTask(
                nodeStack = command.nodeStack,
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
            is TaskRetryScheduled -> event
            is RunWorkflowStarted -> event
            is ForkStarted -> event
            is WorkflowEvent.Outcome -> event
        }
    }

    /**
     * Resumes the workflow execution from a specific task.
     */
    internal suspend fun resumeFromTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
    ): WorkflowEvent {
        // Increment workflow step counter
        val updatedStateStack = nodeStack.incrementStep()

        try {
            val stepResult: StepResult = try {
                // run the next task within a try-catch block to handle workflow-caught exceptions
                tryCatch(node, updatedStateStack) {
                    startTask(updatedStateStack, node, rawInput, flowDirective)
                }
            } catch (e: AsyncTaskException) {
                // Update states of the current node, even if not completed yet
                val states = updatedStateStack.push(node.position to e.state)
                // Return immediately with an event
                return when (e) {
                    is RunWorkflowStartedException -> RunWorkflowStarted(
                        nodeStack = states,
                        runState = e.state,
                        rawInput = e.transformedInput,
                        childConfig = e.config,
                    )

                    is WaitStartedException -> WaitStarted(
                        nodeStack = states,
                        waitState = e.state,
                        rawOutput = e.transformedInput,
                        waitUntil = e.config.waitUntil,
                    )

                    is ForkStartedException -> ForkStarted(
                        nodeStack = states,
                        forkState = e.state,
                        rawInput = e.transformedInput,
                    )
                }
            }

            return getNextEvent(stepResult, node)
        } catch (e: Exception) {
            // Are we within a fork?
            forkBranchFailed(updatedStateStack, node, e)?.let { return it }
            // Uncaught task failure
            return WorkflowFailed(
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
     */
    internal suspend fun resumeFromCompletedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonElement,
    ): WorkflowEvent = try {
        logger.debug { "resumeFromCompletedTask  In: node=${node.position}, output=$rawOutput, states=$nodeStack" }

        // Resume the completed task (transforms output, updates state)
        val stepResult = tryCatch(node, nodeStack) {
            completeTask(nodeStack, node, rawOutput)
        }

        getNextEvent(stepResult, node)
    } catch (e: Exception) {
        // if we are within a fork
        forkBranchFailed(nodeStack, node, e)?.let { return it }
        // Uncaught task failure
        WorkflowFailed(
            nodeStack = nodeStack,
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
        nodeStack: NodeStack,
        node: Node<*>,
        error: InternalException.Error,
    ): WorkflowEvent = try {
        logger.debug { "resumeFromFailedTask  In: node=${node.position}, error=$error, states=$nodeStack" }

        // Resume the failed task (hopefully, the error can be handled)
        val stepResult = tryCatch(node, nodeStack) {
            throw InternalException(error)
        }

        getNextEvent(stepResult, node)
    } catch (e: Exception) {
        // if we are within a fork
        forkBranchFailed(nodeStack, node, e)?.let { return it }
        // Uncaught task failure
        WorkflowFailed(
            nodeStack = nodeStack,
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
        result: StepResult,
        node: Node<*>
    ): WorkflowEvent {
        // Workflow completed
        if (result.nextNode == null) {
            logger.debug { "Workflow completed with output: ${result.nextInput}" }
            return WorkflowCompleted(
                output = result.nextInput,
                completedAt = Clock.System.now(),
                nodeStack = result.nodeStack,
            )
        }

        // The current task must be retried
        if (result.retryAt != null) return TaskRetryScheduled(
            nodeStack = result.nodeStack,
            nodePosition = result.nextNode.position,
            rawInput = result.nextInput,
            flowDirective = result.nextDirective?.toKotlin(),
            retryAt = result.retryAt
        )

        // Are we now completing a fork branch?
        forkBranchCompleted(result, node)?.let { return it }

        // Next Task
        return TaskScheduled(
            nodeStack = result.nodeStack,
            nodePosition = result.nextNode.position,
            rawInput = result.nextInput,
            flowDirective = result.nextDirective?.toKotlin(),
        )
    }

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

    private fun forkBranchCompleted(
        stepResult: StepResult,
        node: Node<*>
    ): ForkBranchCompleted? {
        val nextNode = stepResult.nextNode!!
        val nextState = stepResult.nodeStack[nextNode.position]
        // Is NextNode a fork? Do we enter from Child?
        if (nextNode.task is ForkTask && nextState != null && nextState is ForkState) {
            // Find the branch name
            val branchName = nextNode.children?.find { it.name == node.name }?.name
                ?: throw IllegalStateException("Fork - can not find ${node.name} in ${nextNode.children?.joinToString { it.name }}")

            return ForkBranchCompleted(
                nodeStack = stepResult.nodeStack,
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
        nodeStack: NodeStack,
        block: suspend () -> StepResult
    ): StepResult = try {
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
    ): StepResult {
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
                if (processor.isCatching(exception.error, tryState, nodeStack.lastScope)) {
                    return processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryState,
                        scope = nodeStack.lastScope,
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
    ): StepResult {
        // Check stack top: if it matches this node's position, we're re-entering from child
        val isReEntering = nodeStack.lastPosition == node.position

        return if (!isReEntering) {
            logger.debug { "Entering Down  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput)" }
            // First time entering this node - push a new frame
            node.processor.enterFromParent(rawInput, nodeStack.lastScope, nodeStack)
        } else {
            val state = nodeStack.lastState
            logger.debug {
                "ReEntering Up  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput${
                    flowDirective?.get()?.let { ", flow=$it" } ?: ""
                }), state=$state"
            }
            // Re-entering after a child completed - top of stack is this node's state
            node.processor.enterFromChild(state, flowDirective, rawInput, nodeStack.lastScope, nodeStack)
        }
    }

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     */
    internal fun completeTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonElement
    ): StepResult {
        return node.processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = node.processor.getFlowDirective(),
            parentScope = nodeStack.lastScope,
            taskScope = null,
            nodeStack = nodeStack
        )
    }
}
