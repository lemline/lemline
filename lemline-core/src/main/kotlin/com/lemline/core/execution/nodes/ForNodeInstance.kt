// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.ForTaskState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.expressions.JQExpression
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.ForTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Node instance for ForTask (iteration).
 *
 * ForTask executes its child repeatedly for each item in a collection.
 * Each iteration receives the previous iteration's output as its input.
 *
 * ## Execution Flow
 *
 * 1. Enter: Evaluate collection, initialize state with forIndex = -1
 * 2. Continue: Advance forIndex (0, 1, 2, ...)
 * 3. Check shouldExit: forIndex >= collection.size
 * 4. If not exiting: Enter child with iteration scope variables
 * 5. Re-enter: Receive child output, continue to next iteration
 * 6. Exit: When done iterating, return last iteration's output
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
class ForNodeInstance(
    node: Node<ForTask>,
    parent: NodeInstance<*>?
) : NodeInstance<ForTask>(node, parent) {

    /**
     * State for iteration with collection and variable names.
     */
    override var state: NodeState<*>

    /**
     * Variable names for iteration scope.
     */
    private val itemVarName: String
    private val indexVarName: String

    /**
     * Flag to prevent circular initialization.
     */
    private var isInitializing = false

    init {
        // Get variable names from task definition
        itemVarName = node.task.`for`.each ?: "item"
        indexVarName = node.task.`for`.at ?: "index"

        // Initialize state with empty collection
        // Collection will be evaluated and updated in updateIterationVariables()
        state = ForTaskState(
            collection = emptyList(),
            whileCondition = node.task.`while`,
            itemVarName = itemVarName,
            indexVarName = indexVarName
        )

        // Build child instances (ForTask has one child - the do body)
        children = node.children?.map { childNode ->
            buildNodeInstance(childNode, this)
        } ?: emptyList()
    }

    /**
     * Override scope to include iteration variables.
     *
     * Adds $item and $index to the scope during iteration.
     * Also handles lazy initialization of the collection if needed.
     */
    override val scope: JsonObject
        get() {
            val forState = state as ForTaskState

            // Lazy initialization: if collection is empty, we have rawInput, and we're not already initializing
            if (forState.collection.isEmpty() && rawInput != null && !isInitializing) {
                initializeCollection(rawInput!!)
            }

            // Only add iteration variables if we're in an active iteration
            if (forState.mutable.forIndex >= 0 && forState.mutable.forIndex < forState.collection.size) {
                // Update variables with current item and index
                variables = buildJsonObject {
                    put(itemVarName, forState.getCurrentItem())
                    put(indexVarName, JsonPrimitive(forState.getCurrentIndex()))
                }
            }

            // Return scope from parent (which includes variables)
            return super.scope
        }

    /**
     * Update state after initialization to evaluate the collection.
     *
     * This is called after rawInput is set but before the first child execution.
     * We evaluate the collection expression here.
     */
    fun initializeCollection(transformedInput: JsonElement) {
        // Prevent circular initialization
        if (isInitializing) return
        isInitializing = true

        try {
            val forState = state as ForTaskState

            // Evaluate the collection expression to get the list of items
            val collectionExpr = node.task.`for`.`in`
            val evaluatedCollection = if (collectionExpr != null) {
                // Evaluate the expression using parent scope to avoid circular dependency
                val parentScopeOnly = parent?.scope ?: JsonObject(emptyMap())
                val result = com.lemline.core.expressions.JQExpression.eval(
                    transformedInput,
                    JsonPrimitive(collectionExpr),
                    parentScopeOnly,
                    true  // force = true to always evaluate the expression
                )

                // Convert to list
                when (result) {
                    is kotlinx.serialization.json.JsonArray -> result.toList()
                    else -> {
                        // If not an array, treat as single-item collection
                        listOf(result)
                    }
                }
            } else {
                emptyList()
            }

            // Create new state with evaluated collection
            state = ForTaskState(
                collection = evaluatedCollection,
                whileCondition = forState.whileCondition,
                itemVarName = itemVarName,
                indexVarName = indexVarName,
                startedAt = forState.startedAt,
                mutable = forState.mutable
            )
        } finally {
            isInitializing = false
        }
    }

    /**
     * Evaluate an expression using the protected eval method.
     * Helper to access the private eval method from NodeInstance.
     */
    private fun evaluateExpression(data: JsonElement, expr: String): JsonElement {
        // Use the LemlineJson's JQ evaluator directly
        return JQExpression.eval(
            data,
            JsonPrimitive(expr),
            scope,
            false
        )
    }

    /**
     * Execute ForTask action (flow task, no action).
     *
     * ForTask is a flow control task, not an activity task.
     * It just passes the input through unchanged.
     *
     * @param input Transformed input
     * @return Input unchanged (no action for flow tasks)
     */
    override suspend fun execute(input: JsonElement): JsonElement {
        // Flow task - no action, just return input
        return input
    }
}
