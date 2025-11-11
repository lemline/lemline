@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.complete

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowConfig
import com.lemline.core.errors.ChildWorkflowRequestedException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.execution.BaseOrchestrator
import com.lemline.core.execution.models.StepResult
import com.lemline.core.nodes.Node
import com.lemline.core.states.MutableStates
import com.lemline.core.states.updateWith
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

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
object CompleteOrchestrator : BaseOrchestrator() {

    /**
     * Execute workflow from input to completion with external state management.
     *
     * This is the main entry point for workflow execution. It runs the execution
     * loop until the workflow completes (current becomes null).
     *
     * @param node The current node to execute
     * @param dataset The initial input dataset
     * @return The final output dataset
     * @throws Exception if any error occurs during execution
     */
    suspend fun run(
        node: Node<*>,
        dataset: JsonElement,
        states: MutableStates = mutableMapOf(),
    ): JsonElement {
        var current: Node<*>? = node
        var input: JsonElement = dataset
        var flowDirective: FlowDirective? = null

        while (current != null) {
            val (nextNode, nextInput, nextFlowDirective) = try {
                val result = runStep(current, input, states.toMap(), flowDirective)
                processStepResult(result, states)

            } catch (e: WorkflowException) {
                processWorkflowException(e, current, states)

            } catch (e: ChildWorkflowRequestedException) {
                processChildWorkflowException(e, current, input, states)

            }
            current = nextNode
            input = nextInput
            flowDirective = nextFlowDirective
        }
        return input
    }

    data class StepState(
        val current: Node<*>?,
        var input: JsonElement,
        var flowDirective: FlowDirective?
    )

    private suspend fun processStepResult(
        result: StepResult,
        states: MutableStates
    ): StepState {
        states.updateWith(result.stateUpdates)
        result.newContext?.let { exported ->
            updateRootContext(result.nextNode, states, exported)
        }
        result.delay?.takeIf { it.isPositive() }?.let { delayDuration ->
            handleDelay(delayDuration, "Delaying execution for")
        }
        return StepState(result.nextNode, result.dataset, result.flowDirective)
    }

    private suspend fun processWorkflowException(
        exception: WorkflowException,
        current: Node<*>,
        states: MutableStates
    ): StepState {
        logger.debug { "WorkflowException caught at node: ${current.name}, finding handler..." }
        val result = handleException(current, exception, states.toMap())
        states.updateWith(result.stateUpdates)
        // no delay, no context change
        return StepState(result.nextNode, result.dataset, result.flowDirective)
    }

    private suspend fun processChildWorkflowException(
        exception: ChildWorkflowRequestedException,
        current: Node<*>,
        input: JsonElement,
        states: MutableStates
    ): StepState {
        val childWorkflowConfig = exception.childWorkflowConfig

        // Resolve the sub-workflow definition from the cache
        val subWorkflowRootNode = resolveSubWorkflowNode(childWorkflowConfig)

        val childOutput = if (childWorkflowConfig.awaitCompletion) {
            executeChildWorkflowSync(childWorkflowConfig, subWorkflowRootNode)
        } else {
            executeChildWorkflowAsync(childWorkflowConfig, subWorkflowRootNode, input)
        }

        val completionResult = completeChildWorkflowTask(current, childOutput, states.toMap())
        states.updateWith(completionResult.stateUpdates)
        completionResult.newContext?.let { exported ->
            updateRootContext(completionResult.nextNode, states, exported)
        }
        return StepState(completionResult.nextNode, completionResult.dataset, completionResult.flowDirective)
    }

    /**
     * Resolves the root node of a sub-workflow from the definition cache.
     *
     * @param exception The exception containing the workflow reference
     * @return The root node of the sub-workflow
     * @throws IllegalStateException if the workflow definition is not found
     */
    private fun resolveSubWorkflowNode(exception: ChildWorkflowConfig): Node<*> {
        val namespace = WorkflowNamespace(exception.namespace)
        val name = WorkflowName(exception.name)
        val version = WorkflowVersion(exception.version)

        logger.debug { "Resolving sub-workflow: namespace=$namespace, name=$name, version=$version" }

        val subWorkflow = DefinitionCache.getOrNull(namespace, name, version)
            ?: throw IllegalStateException(
                "Sub-workflow not found: namespace=$namespace, name=$name, version=$version"
            )

        return DefinitionCache.getRootNode(subWorkflow)
    }

    private suspend fun executeChildWorkflowSync(
        exception: ChildWorkflowConfig,
        subWorkflowRootNode: Node<*>
    ): JsonElement {
        logger.debug { "Executing child workflow inline: ${exception.name}" }
        val output = run(subWorkflowRootNode, exception.input, mutableMapOf())
        logger.debug { "Child workflow completed, continuing parent" }
        return output
    }

    private suspend fun executeChildWorkflowAsync(
        exception: ChildWorkflowConfig,
        subWorkflowRootNode: Node<*>,
        parentInput: JsonElement
    ): JsonElement {
        logger.debug { "Launching child workflow asynchronously (fire-and-forget): ${exception.name}" }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                run(subWorkflowRootNode, exception.input, mutableMapOf())
            }.onSuccess {
                logger.debug { "Async child workflow completed successfully" }
            }.onFailure { ex ->
                logger.error(ex) { "Async child workflow failed" }
            }
        }
        return parentInput // Use current input for fire-and-forget
    }

    private suspend fun handleDelay(delayDuration: Duration, logMessage: String) {
        logger.debug { "$logMessage: $delayDuration" }
        delay(delayDuration)
        logger.debug { "Delay completed" }
    }

}
