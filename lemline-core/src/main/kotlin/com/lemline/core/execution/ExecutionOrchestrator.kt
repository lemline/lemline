// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution

import com.lemline.common.logger.logger
import com.lemline.core.execution.models.StepResult
import com.lemline.core.execution.nodes.DoProcessor
import com.lemline.core.execution.nodes.ForProcessor
import com.lemline.core.execution.nodes.NodeProcessor
import com.lemline.core.execution.nodes.SetProcessor
import com.lemline.core.execution.nodes.SwitchProcessor
import com.lemline.core.execution.state.ExprArgs
import com.lemline.core.execution.state.MutableStates
import com.lemline.core.execution.state.NodeState
import com.lemline.core.execution.state.States
import com.lemline.core.execution.state.merge
import com.lemline.core.execution.state.updateWith
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TaskBase
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Main execution loop coordinator for pure functional workflow execution.
 *
 * This object provides the core orchestration functions that implement the
 * pure functional workflow execution model with external state management:
 * - `enter`: First-time entry into a node from parent
 * - `reEnter`: Re-entry into a node from completed child
 * - `continue`: Navigation decision based on flow directive and state
 * - `exitToUp`: Compute output and return to parent
 * - `run`: Main dispatcher between enter and reEnter
 * - `execute`: Full workflow execution loop
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
 * ## Example Usage
 *
 * ```kotlin
 * val workflow = buildNodeInstance(definition)
 * val output = ExecutionOrchestrator.execute(workflow, input)
 * ```
 */
object ExecutionOrchestrator {

    private val logger = logger()

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
    suspend fun run(node: Node<*>, dataset: JsonElement, states: MutableStates = mutableMapOf()): JsonElement {

        var current: Node<*>? = node
        var input: JsonElement = dataset
        var flowDirective: FlowDirective? = null

        while (current != null) {
            try {
                logger.debug { "Executing node: ${current!!.name} with input: $input" }
                // Execute one step - runStep is a pure function
                with(runStep(current, input, states.toMap(), flowDirective)) {
                    current = this.next
                    input = this.dataset
                    flowDirective = this.flowDirective
                    // Apply changes
                    states.updateWith(this.stateUpdates)
                }

                // ← Checkpoint: state is consistent for persistence
                // This is where the states map could be serialized and saved
            } catch (e: Exception) {
                // States unchanged since run() is pure - no rollback needed
                logger.error(e) { "Workflow execution failed at node: ${current?.name ?: "unknown"}" }
                throw e
            }
        }

        return dataset
    }

    /**
     * Execute a single step of workflow execution - pure function.
     *
     * Determines whether to enter node for first time (no state in map)
     * or re-enter after child completion (state exists in map).
     *
     * @param node Current node to execute
     * @param dataset Dataset to process
     * @param states Current states map
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with next node, dataset, deltaStates, and flow directive
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TaskBase> runStep(
        node: Node<T>,
        dataset: JsonElement,
        states: States,
        flowDirective: FlowDirective?
    ): StepResult {
        val state = states[node]
        val exprArgs = getExprArgs(node, states)
        val processor = getNodeProcessor(node, exprArgs)

        return if (state == null) {
            // First time entering this node - doesn't need states
            processor.enterFromParent(dataset)
        } else {
            // Re-entering after a child completed
            // Safe cast: state was created by the same processor type, so types match
            (processor as NodeProcessor<T, NodeState>).enterFromChild(state, flowDirective, dataset)
        }
    }

    /**
     * Retrieves the `ExprArgs` associated with the given node by combining its own expression arguments
     * with those of its parent nodes in the tree, if present.
     *
     * @param current The current `Node` for which to retrieve the expression arguments.
     * @param states The `States` map containing the state information for each node.
     * @return An `ExprArgs` map that combines the expression arguments of the current node and its parent hierarchy.
     */
    private fun getExprArgs(current: Node<*>, states: States): ExprArgs =
        (states[current]?.exprArgs ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getExprArgs(it, states) })


    @Suppress("UNCHECKED_CAST")
    private fun <T : TaskBase> getNodeProcessor(
        node: Node<T>,
        exprArgs: ExprArgs
    ): NodeProcessor<T, *> {
        return when (node.task) {
            is DoTask -> DoProcessor(node as Node<DoTask>, exprArgs)
            is ForTask -> ForProcessor(node as Node<ForTask>, exprArgs)
            is SetTask -> SetProcessor(node as Node<SetTask>, exprArgs)
            is SwitchTask -> SwitchProcessor(node as Node<SwitchTask>, exprArgs)

            else -> throw IllegalArgumentException("Unknown task type: ${node.task::class.simpleName}")
        } as NodeProcessor<T, *>
    }


}
