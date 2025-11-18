// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.ForkException
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.RunWorkflowException
import com.lemline.core.errors.WaitException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.orchestrator.context.merge
import com.lemline.core.orchestrator.sync.ForkSync
import com.lemline.core.orchestrator.sync.RunWorkflowSync
import com.lemline.core.orchestrator.sync.WaitSync
import com.lemline.core.processors.TryProcessor
import com.lemline.core.states.ForkState
import com.lemline.core.states.TaskStates
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.updateWith
import com.lemline.core.workflows.toJava
import com.lemline.core.workflows.toKotlin
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Complete workflow orchestrator that executes workflows from start to finish.
 *
 * This orchestrator implements the standard workflow execution model:
 * - Activities execute via their processors (real HTTP calls, shell commands)
 * - Delays actually wait using coroutines
 * - Sub-workflows execute inline recursively (await=true) or fire-and-forget (await=false)
 * - Returns final workflow output
 *
 * ## Pure Functional Model
 *
 * State is **external to nodes** - stored in `Map<NodePosition, NodeState>`.
 * All functions are pure - they take immutable inputs and return new values:
 *
 * ```
 * while current is not null:
 *     (next, dataset, deltaStates, flowDirective) = run(current, dataset, states, flowDirective)
 *     states = applyDelta(states, deltaStates)  // Apply changes atomically
 *     current = next
 * ```
 *
 * ## No Cloning Needed
 *
 * Since run() is pure:
 * - It never mutates the states map
 * - It returns deltaStates map showing changes
 * - If it throws exception, states is unchanged
 * - applyDelta() creates new map when it succeeds
 *
 * ## Dataset Flow
 *
 * The dataset flows functionally as parameters (never stored):
 * - Down: Parent output → child input
 * - Up: Child output → parent input
 * - Transformed at node boundaries (input.from, output.as)
 *
 * ## Usage
 *
 * - **Synchronous testing**: Test workflows without distributed infrastructure
 * - **Single-node execution**: Run workflows on a single machine
 *
 * For distributed execution with pause/resume, use PausableOrchestrator instead.
 *
 * ## Example
 *
 * ```kotlin
 * val workflow = buildNodeInstance(definition)
 * val output = CompleteOrchestrator.run(workflow, input)
 * ```
 */
@ExperimentalTime
object WorkflowOrchestrator {

    private val logger = logger()

    /**
     * Resumes the execution of a workflow from a given state, continuing the execution based
     * on the current workflow state and execution mode provided.
     *
     * @param workflow The workflow object representing the structure and logic of the process.
     * @param state The current state of the workflow that determines how execution should proceed.
     * @param executionMode The mode of execution to follow during the workflow process.
     * @return The new state of the workflow after resuming its execution.
     */
    suspend fun resume(
        workflow: Workflow,
        state: WorkflowCommand,
        executionMode: ExecutionMode
    ): WorkflowEvent = when (state) {
        // Failed state - retry from failure point (may have rawInput or rawOutput)
        is WorkflowCommand.ResumeFromTask -> resumeFromTask(
            node = workflow.getNode(state.nodePosition),
            rawInput = state.rawInput,
            taskStates = state.taskStates,
            flowDirective = state.flowDirective?.toJava(),
            executionMode = executionMode
        )

        is WorkflowCommand.ResumeFromStartedTask -> resumeFromStartedTask(
            taskStates = state.taskStates,
            node = workflow.getNode(state.nodePosition),
            rawOutput = state.rawOutput,
            executionMode = executionMode
        )
    }

    /**
     * Resume the workflow from a given node.
     *
     * This is the main entry point for workflow execution. It runs the execution
     * loop until the workflow completes (current becomes null).
     *
     * @param node The current node to execute
     * @param rawInput The initial input dataset
     * @return The final output dataset
     * @throws Exception if any error occurs during execution
     */
    internal suspend fun resumeFromTask(
        taskStates: TaskStates = mapOf(),
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
        executionMode: ExecutionMode
    ): WorkflowEvent {
        logger.debug { "resumeFromTask node=${node.reference}, input=$rawInput, flow=$flowDirective, states=$taskStates" }

        try {
            val result: StepResult = try {
                tryCatch(node, taskStates) {
                    runStep(taskStates, node, rawInput, flowDirective)
                }
            } catch (e: WorkflowException) {
                if (executionMode.isAsync()) {
                    // Async: turn exceptions into pause WorkflowState and return immediately
                    return when (e) {
                        is RunWorkflowException -> WorkflowEvent.RunWorkflowStarted(
                            taskStates = taskStates,
                            nodePosition = node.position,
                            runState = e.state,
                            rawInput = e.transformedInput,
                            childConfig = e.config,
                        )

                        is WaitException -> WorkflowEvent.WaitStarted(
                            taskStates = taskStates,
                            nodePosition = node.position,
                            waitState = e.state,
                            rawOutput = e.transformedInput,
                            waitUntil = e.config.waitUntil,
                        )

                        is ForkException -> WorkflowEvent.ForkStarted(
                            taskStates = taskStates,
                            nodePosition = node.position,
                            forkState = e.state,
                            rawInput = e.transformedInput,
                        )

                        is InternalException -> throw e // already handled by tryCatch
                    }
                } else {
                    // Continuous: handle synchronously and continue with a StepResult
                    when (e) {
                        is RunWorkflowException -> RunWorkflowSync.execute(
                            taskStates = taskStates,
                            runWorkflowNode = node,
                            transformedInput = e.transformedInput,
                            config = e.config,
                            executionMode = executionMode
                        )

                        is WaitException -> WaitSync.execute(
                            taskStates = taskStates,
                            waitNode = node,
                            transformedInput = e.transformedInput,
                            waitUntil = e.config.waitUntil,
                            executionMode = executionMode
                        )

                        is ForkException -> ForkSync.execute(
                            taskStates = taskStates,
                            forkNode = node,
                            forkState = e.state,
                            rawInput = e.transformedInput,
                            executionMode = executionMode
                        )

                        is InternalException -> throw e // defensive: shouldn't reach here
                    }
                }
            }

            // Create an updated states map
            val newStates = taskStates.updateWith(result.stateUpdates, result.nextContext)

            // Workflow completed
            if (result.nextNode == null) {
                logger.debug { "Workflow completed with output: $rawInput" }
                return WorkflowEvent.WorkflowCompleted(output = rawInput)
            }

            // The current task must be retried
            if (result.retryAt != null) {
                if (executionMode.isAsync()) return WorkflowEvent.RetryScheduled(
                    taskStates = newStates,
                    nodePosition = result.nextNode.position,
                    rawInput = result.nextInput,
                    flowDirective = result.nextDirective?.toKotlin(),
                    retryAt = result.retryAt
                )
                // wait before retry
                WaitSync.executeDelay(result.retryAt, "Retrying at node: ${node.name} after")
            }

            // if we are now completing a fork branch
            getCompletedForkBranch(result.nextNode, newStates, node)?.let {
                return WorkflowEvent.ForkBranchCompleted(
                    taskStates = newStates,
                    nodePosition = result.nextNode.position,
                    branchName = it,
                    output = result.nextInput,
                    flowDirective = result.nextDirective?.toKotlin(),
                )
            }

            // Check if we should stop after this task
            if (executionMode.stopAfterTaskCompletion(node)) return WorkflowEvent.TaskScheduled(
                taskStates = newStates,
                nodePosition = result.nextNode.position,
                rawInput = result.nextInput,
                flowDirective = result.nextDirective?.toKotlin(),
            )

            // Continue with the next iteration
            return resumeFromTask(
                taskStates = newStates,
                node = result.nextNode,
                rawInput = result.nextInput,
                flowDirective = result.nextDirective,
                executionMode = executionMode
            )

        } catch (e: Exception) {
            return WorkflowEvent.TaskFailed(
                taskStates = taskStates.toMap(),
                nodePosition = node.position,
                rawInput = rawInput,
                rawOutput = null,
                flowDirective = flowDirective?.toKotlin(),
                exception = e
            )
        }
    }

    private fun getCompletedForkBranch(nextNode: Node<*>, taskStates: TaskStates, current: Node<*>): String? {
        val nextState = taskStates[nextNode.position]
        if (nextNode.task is ForkTask && nextState != null && nextState is ForkState) {
            // nextNode is a fork, we enter from child, now let's find the branch name
            return nextNode.children?.find { it.name == current.name }?.name
                ?: throw IllegalStateException("Fork - can not find ${current.name} in ${nextNode.children?.joinToString { it.name }}")
        }
        return null
    }

    internal suspend fun resumeFromStartedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        taskStates: TaskStates,
        executionMode: ExecutionMode
    ): WorkflowEvent = run {
        logger.debug { "resumeFromInterruptedTask In: node=${node.reference}, output=$rawOutput, states=$taskStates" }

        try {
            // Complete the interrupted task (transforms output, updates state)
            val result = tryCatch(node, taskStates) {
                completeStartedTask(taskStates, node, rawOutput)
            }

            // Create new states map with updated state updates and context exports
            val newStates = taskStates.updateWith(result.stateUpdates, result.nextContext)

            // Continue execution from the next node (may pause again or complete)
            return@run resumeFromTask(
                taskStates = newStates,
                node = result.nextNode ?: return WorkflowEvent.WorkflowCompleted(result.nextInput),
                rawInput = result.nextInput,
                flowDirective = result.nextDirective,
                executionMode = executionMode
            )
        } catch (e: Exception) {
            return@run WorkflowEvent.TaskFailed(
                taskStates = taskStates,
                nodePosition = node.position,
                rawInput = null,
                rawOutput = rawOutput,
                flowDirective = null,
                exception = e
            )
        }
    }.also {
        logger.debug { "resumeFromInterruptedTask Out: state=$it" }
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
     */
    private fun processInternalWorkflowException(
        exception: InternalException,
        failingNode: Node<*>,
        taskStates: TaskStates,
    ): StepResult {
        // Find the nearest TryTask that can handle this error
        var tryNode: Node<*>? = failingNode

        while (tryNode != null) {
            if (tryNode.task is TryTask) {
                @Suppress("UNCHECKED_CAST")
                tryNode as Node<TryTask>
                // current scope of the try node
                val tryScope = getScope(tryNode, taskStates)
                // current state of the try node
                val tryState = taskStates[tryNode.position] as TryState
                // build a processor for the try node
                val processor = ProcessorFactory.getProcessor(tryNode) as TryProcessor
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
            tryNode = tryNode.parent
        }
        // No handler found - fail workflow
        throw exception
    }

    /**
     * Execute a single step of workflow execution - pure function.
     *
     * Determines whether we enter the node for the first time (no state)
     * or re-enter after child completion (state exists).
     *
     * @param node Current node to execute
     * @param rawInput Dataset to process
     * @param taskStates Current states map
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with: next node, dataset, deltaStates, and flow directive
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun runStep(
        taskStates: TaskStates,
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective?,
    ): StepResult {
        val state = taskStates[node.position]
        val scope = getScope(node, taskStates)
        val processor = ProcessorFactory.getProcessor(node)

        return if (state == null) {
            logger.debug { "Entering Down  node=${node.reference}, rawInput=$rawInput" }
            // First time entering this node
            processor.enterFromParent(rawInput, scope)
        } else {
            logger.debug {
                "ReEntering Up  node=${node.reference}, transformedInput=$rawInput${
                    flowDirective?.get()?.let { ", flow=$it" } ?: ""
                }, state=$state"
            }
            // Re-entering after a child completed
            processor.enterFromChild(state, flowDirective, rawInput, scope)
        }
    }


    /**
     * Retrieves the `Scope` associated with the given node by combining its own expression arguments
     * with those of its parent nodes in the tree, if present.
     */
    private fun getScope(current: Node<*>, taskStates: TaskStates): Scope =
        (taskStates[current.position]?.scope ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getScope(it, taskStates) })

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     *
     * @param node The current node that was interrupted
     * @param rawOutput The output from the interrupted task
     * @param taskStates The current workflow states
     * @return StepResult containing the next node, dataset, and state updates
     */
    internal fun completeStartedTask(
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
            taskContext = null
        )
    }

}
