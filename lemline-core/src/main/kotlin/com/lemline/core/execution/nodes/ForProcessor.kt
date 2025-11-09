// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.ExprArgs
import com.lemline.core.execution.state.ForState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForTask
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for ForTask (iteration) - pure functional model.
 *
 * ForTask executes its child repeatedly for each item in a collection.
 * Each iteration receives the previous iteration's output as its input.
 *
 * ## Example Workflow
 *
 * ```yaml
 * processOrders:
 *   for:
 *     each: order      # itemVarName
 *     at: orderIndex   # indexVarName
 *     in: .orders      # Expression to evaluate collection
 *   do:
 *     - processOrder:
 *         set:
 *           id: $order.id         # Access via scope variable
 *           position: $orderIndex  # Access via scope variable
 * ```
 *
 * Input: `{ orders: [{id: 1}, {id: 2}] }`
 * Iteration 0: scope has `$order = {id: 1}`, `$orderIndex = 0`
 * Iteration 1: scope has `$order = {id: 2}`, `$orderIndex = 1`
 * Output: Result from last iteration's processOrder
 *
 * ## Scope Variables
 *
 * ForTask adds iteration-specific variables to the **scope** (not dataset):
 * - `$item` (or custom name from `each`): Current item from collection
 * - `$index` (or custom name from `at`): Current iteration index (0-based)
 *
 * Children access these via JQ expressions (e.g., `$order.id`).
 *
 * ## Dataset Flow
 *
 * - **First iteration**: Receives ForTask's transformed input
 * - **Subsequent iterations**: Each receives previous iteration's output
 * - **Final output**: Last iteration's output (or original input if collection is empty)
 *
 * @property node Immutable ForTask definition
 * @property parent Parent node instance
 */
class ForProcessor(
    node: Node<ForTask>
) : NodeProcessor<ForTask, ForState>(node) {

    override fun createState(dataset: JsonElement, exprArgs: ExprArgs): ForState = ForState(
        startedAt = Clock.System.now(),
        collection = evalForIn(dataset, exprArgs),
        index = -1
    )

    override fun getNextStepInfo(
        state: ForState,
        dataset: JsonElement,
        nodeName: String?
    ): Triple<NodeState?, Node<*>?, FlowDirective?> {
        // get an updated state for the current node
        val updatedState = getNextState(state)

        // Note: evalWhile needs exprArgs but we don't have it here
        // This will be fixed when we update getNextStepInfo signature
        // For now, we'll use a workaround with empty exprArgs
        return when (updatedState.index >= updatedState.collection!!.size) {
            true -> Triple(null, node.parent, getFlowDirective()) // <= clear the state, target the parent
            false -> Triple(
                updatedState,
                node.children?.firstOrNull(),
                null
            ) // <= update the state, target the "Do" node,
        }
    }

    // Update the state without copying the collection
    private fun getNextState(state: ForState): ForState = ForState(
        startedAt = state.startedAt,
        collection = state.collection,
        index = state.index + 1
    )

    private fun evalWhile(dataset: JsonElement, exprArgs: ExprArgs): Boolean {
        val whileCondition = node.task.`while` ?: return true
        return evalBoolean(dataset, whileCondition, "while", exprArgs)
    }

    private fun evalForIn(dataset: JsonElement, exprArgs: ExprArgs) =
        evalList(dataset, node.task.`for`.`in`, "for.in", exprArgs)
}
