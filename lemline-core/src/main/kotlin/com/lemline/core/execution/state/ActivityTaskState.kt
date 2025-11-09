// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Mutable state for ActivityTask (serialized for resumption).
 *
 * Activity tasks are leaf nodes that execute actions (Set, CallHTTP, Emit, etc.)
 * and have no children. They execute once and immediately exit to parent.
 *
 * @property hasExecuted Whether the action has been executed
 */
@Serializable
data class ActivityMutableState(
    val hasExecuted: Boolean = false
)

/**
 * Complete state for ActivityTask (leaf nodes with actions).
 *
 * ActivityTask represents tasks that perform actions but have no children:
 * - SetTask: Sets data values
 * - CallHTTP: Makes HTTP requests
 * - EmitTask: Emits events
 * - RunTask: Runs child workflows
 * - WaitTask: Waits for duration
 * - RaiseTask: Raises errors
 *
 * ## State Separation
 *
 * **Immutable** (cached, not serialized):
 * - startedAt: When task started
 *
 * **Mutable** (serialized):
 * - hasExecuted: Whether action has been executed
 *
 * ## Execution Flow
 *
 * 1. Enter: Initialize state (hasExecuted = false)
 * 2. Continue: Check shouldExit()
 * 3. ExitToUp: Execute action, transform output, exit to parent
 *
 * Activity tasks never have children, so they never re-enter from below.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - setStatus:
 *       set:
 *         status: "processed"
 *   - callAPI:
 *       call: http
 *       with:
 *         url: https://api.example.com
 *   - emitEvent:
 *       emit:
 *         event: "orderProcessed"
 * ```
 *
 * @property startedAt When task started (immutable, cached)
 * @property mutable Serializable runtime state
 */
class ActivityTaskState(
    override var startedAt: Instant? = null,
    override var mutable: ActivityMutableState = ActivityMutableState()
) : NodeState<ActivityMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        // Activity tasks exit immediately after execution
        return true
    }

    override fun nextChildIndex(): Int {
        throw UnsupportedOperationException("Activity tasks don't have children")
    }

    override fun applyFlowDirective(gotoTarget: String?) {
        // Activity tasks don't process flow directives
        // (flow directive is returned to parent)
    }

    override fun clear() {
        super.clear()
        mutable = ActivityMutableState()
    }

    override fun clone(): NodeState<ActivityMutableState> {
        return ActivityTaskState(
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }
}
