// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Mutable state for DoTask (serialized for resumption).
 *
 * Only contains the minimal runtime state needed to resume execution:
 * - Current child index
 *
 * @property childIndex Current child position (-1 means not started, 0+ means executing child at that index)
 */
@Serializable
data class DoMutableState(
    val childIndex: Int = -1
)

/**
 * Complete state for DoTask (sequential execution).
 *
 * DoTask executes its children sequentially in order, advancing from one child
 * to the next until all are complete.
 *
 * ## State Separation
 *
 * **Immutable** (cached, not serialized):
 * - `doSize`: Number of children (from definition)
 * - `startedAt`: When task started
 *
 * **Mutable** (serialized):
 * - `childIndex`: Current position in child list
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - validateOrder:
 *       call: http
 *   - processOrder:
 *       set: { status: "processed" }
 *   - notifyCustomer:
 *       emit: { event: "orderProcessed" }
 * ```
 *
 * State transitions:
 * 1. Enter: `childIndex = -1` (not started)
 * 2. First continue: `childIndex = 0` (validateOrder)
 * 3. Re-enter after validateOrder: `childIndex = 1` (processOrder)
 * 4. Re-enter after processOrder: `childIndex = 2` (notifyCustomer)
 * 5. Re-enter after notifyCustomer: `childIndex = 3` (>= doSize, exit)
 *
 * @property doSize Number of children (immutable, from definition)
 * @property startedAt When task started (immutable, cached)
 * @property mutable Serializable runtime state
 */
class DoTaskState(
    /**
     * Number of children (immutable, cached from definition).
     */
    val doSize: Int,

    /**
     * Number of children by name (immutable, cached from definition).
     * Used for Goto navigation to find child index by name.
     */
    val childNames: Map<String, Int>,

    override var startedAt: Instant? = null,
    override var mutable: DoMutableState = DoMutableState()
) : NodeState<DoMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        return mutable.childIndex >= doSize
    }

    override fun nextChildIndex(): Int {
        return mutable.childIndex
    }

    override fun applyFlowDirective(gotoTarget: String?) {
        if (gotoTarget != null) {
            // Jump to named child
            val targetIndex = findChildIndexByName(gotoTarget)
            mutable = mutable.copy(childIndex = targetIndex)
        } else {
            // Default continue: advance to next child
            mutable = mutable.copy(childIndex = mutable.childIndex + 1)
        }
    }

    override fun clear() {
        super.clear()
        mutable = DoMutableState()
    }

    override fun clone(): NodeState<DoMutableState> {
        return DoTaskState(
            doSize = doSize,
            childNames = childNames,
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    /**
     * Find child index by name for Goto navigation.
     *
     * @param name Task name to find
     * @return Child index
     * @throws IllegalArgumentException if name not found
     */
    private fun findChildIndexByName(name: String): Int {
        return childNames[name] ?: throw IllegalArgumentException("Task not found: $name")
    }
}
