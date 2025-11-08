// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Base class for node execution state.
 *
 * Separates **immutable** fields (cached in-memory, never serialized) from
 * **mutable** fields (serialized for resumption).
 *
 * ## State Architecture
 *
 * Node state consists of two parts:
 * 1. **Immutable fields**: Computed once from definition or parent output, cached in-memory
 *    - Example: `startedAt`, `collection`, `doSize`, `maxAttempts`
 *    - Never serialized (recomputed on resume)
 *
 * 2. **Mutable fields**: Runtime state that changes during execution
 *    - Example: `childIndex`, `forIndex`, `attemptIndex`
 *    - Serialized as minimal data class
 *
 * ## Benefits
 *
 * - **Smaller serialization**: Only mutable indices/flags serialized (60-95% reduction)
 * - **Efficient state updates**: Copy small mutable data class instead of entire state
 * - **Clear separation**: Immutable cached data vs. resumable runtime state
 *
 * ## Usage
 *
 * ```kotlin
 * class DoTaskState(
 *     val doSize: Int,                    // Immutable - cached from definition
 *     override var startedAt: Instant? = null,
 *     override var mutable: DoMutableState = DoMutableState()
 * ) : NodeState<DoMutableState>(startedAt, mutable)
 * ```
 *
 * @param M The mutable state type (must be a data class for efficient serialization)
 */
abstract class NodeState<M>(
    /**
     * When the node started execution (immutable, cached).
     * Not serialized - recomputed from parent state on resume.
     */
    open var startedAt: Instant? = null,

    /**
     * Mutable state that gets serialized for resumption.
     * This is the only part of node state that is persisted.
     */
    open var mutable: M
) {

    /**
     * Check if node has completed its work and should exit to parent.
     *
     * @return true if node is done, false if more work to do
     */
    abstract fun shouldExit(): Boolean

    /**
     * Get index of next child to process.
     *
     * @return Child index, or -1 if no children (activity tasks)
     */
    abstract fun nextChildIndex(): Int

    /**
     * Initialize state with transformed input (called once at enter).
     *
     * Some node types cache data from the input for later use:
     * - ForTask: Evaluates collection expression and caches result
     * - TryTask: Caches transformedInput for retries
     *
     * Default implementation does nothing.
     *
     * @param transformedInput The task's input after `input.from` transformation
     */
    open fun init(transformedInput: JsonElement) {
        // Default: no initialization needed
    }

    /**
     * Update state when re-entering from child (called at reEnter).
     *
     * Some node types need to process child results:
     * - ListenInstance: Accumulates events from child
     * - ForkInstance: Tracks branch completion
     *
     * Default implementation does nothing.
     *
     * @param datasetFromChild The output returned by the completed child
     */
    open fun updateFromChild(datasetFromChild: JsonElement) {
        // Default: no update needed
    }

    /**
     * Apply flow directive to update state (called at continueNavigation).
     *
     * This is where the node decides where to go next:
     * - null: Default continue behavior (advance to next child)
     * - String: Goto named child
     * - Exit/End: Handled by continueNavigation() function, not state
     *
     * @param gotoTarget Target task name for goto, or null for default continue
     */
    abstract fun applyFlowDirective(gotoTarget: String?)

    /**
     * Clear state for cleanup when node exits to parent.
     *
     * Resets both immutable and mutable fields to initial state.
     * The state entry will be removed from the InstanceState.nodeStates map.
     */
    open fun clear() {
        startedAt = null
        // Subclasses should reset mutable state to initial values
    }

    /**
     * Clone state for rollback on exception.
     *
     * Creates a deep copy of the state so it can be restored if execution fails.
     * This is critical for state atomicity - state is either fully updated or fully rolled back.
     *
     * @return Deep copy of this state
     */
    abstract fun clone(): NodeState<M>
}
