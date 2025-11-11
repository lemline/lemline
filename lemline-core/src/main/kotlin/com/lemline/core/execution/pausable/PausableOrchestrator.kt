@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.pausable

import com.lemline.core.errors.ChildWorkflowRequestedException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.execution.BaseOrchestrator
import com.lemline.core.nodes.Node
import com.lemline.core.states.MutableStates
import com.lemline.core.states.updateWith
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Pausable workflow orchestrator that can stop execution at specific boundaries.
 *
 * This orchestrator implements distributed execution with pause/resume:
 * - Activities execute via their processors (real HTTP calls, shell commands)
 * - **After activity completes**: Stops and returns control to runner for persistence
 * - **When delay needed**: Stops instead of waiting, returns duration to runner
 * - **Before sub-workflow**: Stops to allow parent-child persistence
 * - Returns [PausableResult] indicating what happened (activity, delay, sub-workflow, or complete)
 *
 * ## Stopping Point Detection
 *
 * The orchestrator checks for stopping points **after each step completes**:
 * - Activity completed? → Check if current node is activity type (HTTP, Shell, Script)
 * - Delay needed? → Check if step result contains delay duration
 * - Sub-workflow needed? → Catch ChildWorkflowStartedException
 *
 * ## State Consistency
 *
 * All stops happen after the step completes, ensuring:
 * - Activity has fully executed (response received)
 * - State updates have been applied
 * - Output is available and can be saved
 * - No risk of partial state
 *
 * ## Usage
 *
 * - **Distributed execution**: Run workflows across multiple workers
 * - **State persistence**: Stop at boundaries to save state to database
 * - **Resume in different workers**: Any worker can continue from saved state
 *
 * For synchronous testing without pause/resume, use CompleteOrchestrator instead.
 *
 * ## Example
 *
 * ```kotlin
 * val workflow = buildNodeInstance(definition)
 * when (val result = PausableOrchestrator.run(workflow, input)) {
 *     is PausableResult.Complete -> handleComplete(result.output)
 *     is PausableResult.ActivityCompleted -> persistAndContinue(result.nextNode, result.states, result.output)
 *     is PausableResult.WaitNeeded -> scheduleWait(result.nextNode, result.states, result.duration)
 *     is PausableResult.RetryNeeded -> scheduleRetry(result.nextNode, result.states, result.duration)
 *     is PausableResult.SubWorkflowNeeded -> startChild(result.nextNode, result.states, result.childConfig, result.awaitCompletion)
 * }
 * ```
 */
object PausableOrchestrator : BaseOrchestrator() {

    /**
     * Execute workflow step-by-step, pausing at boundaries.
     *
     * This is the main entry point for pausable execution. It runs the execution
     * loop until hitting a stopping point (activity, delay, sub-workflow) or
     * completing the workflow.
     *
     * @param node The current node to execute
     * @param dataset The initial input dataset
     * @param states The current workflow states (mutable, will be updated)
     * @return PausableResult indicating what happened (pause or complete)
     * @throws Exception if any unhandled error occurs during execution
     */
    suspend fun run(
        node: Node<*>,
        dataset: JsonElement,
        states: MutableStates = mutableMapOf(),
    ): PausableResult {
        var current: Node<*>? = node
        var input: JsonElement = dataset
        var flowDirective: FlowDirective? = null

        while (current != null) {
            try {
                logger.debug { "Executing node: ${current!!.reference} with input: $input" }
                val result = runStep(current, input, states.toMap(), flowDirective)

                // Apply state updates
                states.updateWith(result.stateUpdates)
                result.newContext?.let { exported ->
                    updateRootContext(result.nextNode, states, exported)
                }

                // Check for stopping points AFTER step completes

                // 1. Check if we just completed an activity
                if (isActivityNode(current)) {
                    logger.debug { "Activity completed, pausing execution" }
                    return PausableResult.ActivityCompleted(
                        nextNodePosition = result.nextNode?.reference,
                        states = states.toMap(),
                        output = result.dataset
                    )
                }

                // 2. Check if wait is needed (from WaitTask)
                result.delay?.takeIf { it.isPositive() }?.let { delayDuration ->
                    logger.debug { "Wait needed: $delayDuration, pausing execution" }
                    return PausableResult.WaitNeeded(
                        nextNodePosition = result.nextNode?.reference,
                        states = states.toMap(),
                        duration = delayDuration
                    )
                }

                // No stopping point - continue to next step
                current = result.nextNode
                input = result.dataset
                flowDirective = result.flowDirective

            } catch (e: WorkflowException) {
                val failingNode = current!!
                logger.debug { "WorkflowException caught at node: ${failingNode.name}, finding handler..." }
                val result = handleException(failingNode, e, states.toMap())
                states.updateWith(result.stateUpdates)

                result.delay?.takeIf { it.isPositive() }?.let { delayDuration ->
                    logger.debug { "Retry needed: $delayDuration, pausing execution" }
                    return PausableResult.RetryNeeded(
                        nodePosition = result.nextNode!!.reference,
                        states = states.toMap(),
                        duration = delayDuration
                    )
                }

                current = result.nextNode
                input = result.dataset
                flowDirective = result.flowDirective

            } catch (e: ChildWorkflowRequestedException) {

                // Sub-workflow needs to be started
                return PausableResult.SubWorkflowNeeded(
                    nodePosition = current!!.reference,
                    states = states.toMap(),
                    childConfig = e.config,
                )
            }
        }

        // Workflow completed
        logger.debug { "Workflow completed with output: $input" }
        return PausableResult.Complete(output = input)
    }

    /**
     * Checks if a node represents an activity that should trigger a pause.
     *
     * Activities are tasks that perform external interactions:
     * - CallHTTP: HTTP/REST API calls
     * - RunShell: Shell command execution
     * - RunScript: Script execution (JS, Python, etc.)
     *
     * @param node The node to check
     * @return true if this is an activity node, false otherwise
     */
    private fun isActivityNode(node: Node<*>): Boolean {
        return when (node.task) {
            is CallHTTP -> true
            is RunTask -> {
                val runTask = node.task as RunTask
                when (runTask.run.get()) {
                    is RunShell -> true
                    is RunScript -> true
                    else -> false
                }
            }

            else -> false
        }
    }
}
