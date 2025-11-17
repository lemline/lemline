// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.ForTaskState
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
 */
class ForProcessor(
    node: Node<ForTask>
) : NodeProcessor<ForTask, ForTaskState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope) = ForTaskState(
        startedAt = Clock.System.now(),
        collection = evalForIn(transformedInput, scope),
        index = -1,
        forEach = node.task.`for`.each ?: "item",
        forAt = node.task.`for`.at ?: "index"
    )

    override fun getNextStepInfo(
        state: ForTaskState,
        dataset: JsonElement,
        scope: Scope,
        namedNode: String?,
    ): NextStepInfo {
        // get an updated state for the current node
        val updatedState = getNextState(state)

        // Check if we should continue looping (while condition and collection bounds)
        return when (shouldContinue(updatedState, dataset, scope)) {
            false -> NextStepInfo(
                updatedState = null,
                nextNode = node.parent,
                flowDirective = getFlowDirective()
            )

            true -> NextStepInfo(
                updatedState = updatedState,
                nextNode = getDoNode(),
                flowDirective = null
            )
        }
    }

    // At each iteration, we remove the first item from the collection and increment the index
    private fun getNextState(state: ForTaskState): ForTaskState = ForTaskState(
        startedAt = state.startedAt,
        collection = if (state.index >= 0) state.collection.drop(1) else state.collection,
        index = state.index + 1,
        forEach = node.task.`for`.each ?: "item",
        forAt = node.task.`for`.at ?: "index"
    )

    private fun shouldContinue(updatedState: ForTaskState, transformedInput: JsonElement, scope: Scope) =
        updatedState.collection.isNotEmpty() && evalWhile(transformedInput, scope)

    private fun evalWhile(dataset: JsonElement, scope: Scope): Boolean {
        val whileCondition = node.task.`while` ?: return true
        return evalBoolean(dataset, whileCondition, "while", scope)
    }

    private fun evalForIn(dataset: JsonElement, scope: Scope): List<JsonElement> {
        // For.in is evaluated during createState, before the full context exists
        return evalList(dataset, node.task.`for`.`in`, "for.in", scope)
    }

    private fun getDoNode() = node.children?.firstOrNull() ?: throw NoSuchElementException("ForTask has no do task")
}
