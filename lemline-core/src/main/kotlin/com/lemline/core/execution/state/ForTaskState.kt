// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mutable state for ForTask (serialized for resumption).
 *
 * Only contains the minimal runtime state needed to resume execution:
 * - Current iteration index
 *
 * @property forIndex Current iteration index (-1 means not started, 0+ means executing iteration at that index)
 */
@Serializable
data class ForMutableState(
    val forIndex: Int = -1
)

/**
 * Complete state for ForTask (iteration).
 *
 * ForTask executes its child repeatedly for each item in a collection.
 * The child receives the previous iteration's output as its input.
 *
 * ## State Separation
 *
 * **Immutable** (cached, not serialized):
 * - `collection`: List of items to iterate over (evaluated once from `for.in`)
 * - `whileCondition`: Optional while expression (from definition)
 * - `itemVarName`: Variable name for current item (default: "item", from `for.each`)
 * - `indexVarName`: Variable name for current index (default: "index", from `for.at`)
 * - `startedAt`: When task started
 *
 * **Mutable** (serialized):
 * - `forIndex`: Current iteration index
 *
 * ## Example Workflow
 *
 * ```yaml
 * processOrders:
 *   for:
 *     each: order      # itemVarName
 *     at: orderIndex   # indexVarName
 *     in: .orders      # Evaluated once to produce collection
 *   do:
 *     - processOrder:
 *         set:
 *           id: $order.id         # Access via scope variable
 *           position: $orderIndex  # Access via scope variable
 * ```
 *
 * ## Iteration Variables
 *
 * ForTask adds iteration-specific variables to the **scope** (not dataset):
 * - `$item` (or custom name): Current item from collection
 * - `$index` (or custom name): Current iteration index (0-based)
 *
 * Children access these via expressions (e.g., `$order.id`), but they are NOT
 * merged into the dataset.
 *
 * ## Dataset Flow
 *
 * - **Across iterations**: Each iteration receives previous iteration's output
 * - **First iteration**: Receives ForTask's `transformedInput`
 * - **Output**: Returns last child's output from last iteration
 *
 * @property collection Items to iterate over (immutable, evaluated once)
 * @property whileCondition Optional while expression (immutable, from definition)
 * @property itemVarName Variable name for current item (immutable, from `for.each` or default "item")
 * @property indexVarName Variable name for current index (immutable, from `for.at` or default "index")
 * @property startedAt When task started (immutable, cached)
 * @property mutable Serializable runtime state
 */
class ForTaskState(
    /**
     * Collection to iterate over (immutable, cached from `for.in` evaluation).
     */
    val collection: List<JsonElement>,

    /**
     * While condition expression (immutable, from definition).
     * Iteration continues while this expression evaluates to true.
     */
    val whileCondition: String? = null,

    /**
     * Iteration variable names (immutable, from definition).
     */
    val itemVarName: String = "item",
    val indexVarName: String = "index",

    override var startedAt: Instant? = null,
    override var mutable: ForMutableState = ForMutableState()
) : NodeState<ForMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        // Check if past end of collection
        if (mutable.forIndex >= collection.size) return true

        // Check while condition if present
        // Note: while condition evaluation requires scope, which is built by the node
        // The node will call shouldExit() with access to scope for evaluation
        // For now, we just check the index boundary
        return false
    }

    override fun nextChildIndex(): Int {
        // Always return 0 (the do body) - same child, different iteration
        return 0
    }

    override fun applyFlowDirective(gotoTarget: String?) {
        if (gotoTarget != null) {
            // Goto not supported in ForTask (only one child)
            throw UnsupportedOperationException("Goto not supported in ForTask")
        } else {
            // Default continue: advance to next iteration
            mutable = mutable.copy(forIndex = mutable.forIndex + 1)
        }
    }

    override fun clear() {
        super.clear()
        mutable = ForMutableState()
    }

    override fun clone(): NodeState<ForMutableState> {
        return ForTaskState(
            collection = collection,
            whileCondition = whileCondition,
            itemVarName = itemVarName,
            indexVarName = indexVarName,
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    /**
     * Get current item for scope variables.
     * Used by node to add `$item` to scope during iteration.
     *
     * @return Current item from collection
     * @throws IndexOutOfBoundsException if forIndex is out of bounds
     */
    fun getCurrentItem(): JsonElement {
        return collection[mutable.forIndex]
    }

    /**
     * Get current index for scope variables.
     * Used by node to add `$index` to scope during iteration.
     *
     * @return Current iteration index (0-based)
     */
    fun getCurrentIndex(): Int {
        return mutable.forIndex
    }
}
