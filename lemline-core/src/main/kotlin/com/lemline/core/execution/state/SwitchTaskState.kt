// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Mutable state for SwitchTask (serialized for resumption).
 *
 * @property selectedCase Which case was selected (index in switch.cases list)
 * @property hasExecuted Whether the selected case has been executed
 */
@Serializable
data class SwitchMutableState(
    val selectedCase: Int,
    val hasExecuted: Boolean = false
)

/**
 * Complete state for SwitchTask (conditional branching).
 *
 * SwitchTask evaluates case conditions and executes one matching branch.
 *
 * ## State Separation
 *
 * **Immutable** (cached, not serialized):
 * - Case definitions (from workflow definition)
 * - startedAt: When task started
 *
 * **Mutable** (serialized):
 * - selectedCase: Which case was selected
 * - hasExecuted: Whether the case has been executed
 *
 * ## Example Workflow
 *
 * ```yaml
 * routeOrder:
 *   switch:
 *     - urgent:
 *         when: .priority == "urgent"
 *         then: processUrgent
 *         do:
 *           - handleUrgent: ...
 *     - normal:
 *         when: .priority == "normal"
 *         do:
 *           - handleNormal: ...
 *     - default:  # No 'when' - matches all
 *         do:
 *           - handleDefault: ...
 * ```
 *
 * ## Case Selection
 *
 * Cases are evaluated in order at enter time. The first case with:
 * - No `when` condition (matches all), OR
 * - `when` condition evaluates to true
 *
 * is selected and executed.
 *
 * ## Flow Directive
 *
 * After the case executes, its `then` directive is used for navigation
 * (e.g., jump to named sibling, exit, etc.).
 *
 * @property startedAt When task started (immutable, cached)
 * @property mutable Serializable runtime state
 */
class SwitchTaskState(
    override var startedAt: Instant? = null,
    override var mutable: SwitchMutableState
) : NodeState<SwitchMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        // Done after executing the selected case
        return mutable.hasExecuted
    }

    override fun nextChildIndex(): Int {
        // Return the selected case index
        return mutable.selectedCase
    }

    override fun applyFlowDirective(gotoTarget: String?) {
        if (gotoTarget != null) {
            // Goto not supported within SwitchTask
            throw UnsupportedOperationException("Goto not supported within SwitchTask")
        } else {
            // Default continue: mark as executed after case completes
            mutable = mutable.copy(hasExecuted = true)
        }
    }

    override fun clear() {
        super.clear()
        mutable = SwitchMutableState(selectedCase = -1, hasExecuted = false)
    }

    override fun clone(): NodeState<SwitchMutableState> {
        return SwitchTaskState(
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    companion object {
        /**
         * Select which case to execute based on conditions.
         *
         * This is called once at enter time to determine which case matches.
         * The selected case index is then stored in mutable state.
         *
         * @param cases List of case definitions
         * @param evaluateCondition Function to evaluate a case condition
         * @return Index of selected case
         * @throws IllegalStateException if no case matches
         */
        fun selectCase(
            cases: List<SwitchCase>,
            evaluateCondition: (String?) -> Boolean
        ): Int {
            for ((index, case) in cases.withIndex()) {
                // No 'when' condition means match all (default case)
                if (case.`when` == null) return index

                // Evaluate condition
                if (evaluateCondition(case.`when`)) return index
            }

            throw IllegalStateException("No switch case matched")
        }
    }
}

/**
 * Switch case definition.
 *
 * @property name Case name (optional, from YAML key)
 * @property `when` Condition expression (null means match all)
 */
data class SwitchCase(
    val name: String?,
    val `when`: String?
)
