// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Default task state implementation for simple tasks that don't require additional state tracking.
 * Used by: Call, Run, Raise, Wait, Set, Fork, and Switch tasks.
 */
@Serializable
data class TaskState(
    override val startedAt: Instant = Clock.System.now(),
) : NodeState()

// Type aliases for semantic clarity - each represents a specific task type
typealias RunState = TaskState
typealias RaiseState = TaskState
typealias WaitState = TaskState
typealias SetState = TaskState
typealias ForkState = TaskState
typealias SwitchState = TaskState
typealias EmitState = TaskState
typealias ListenState = TaskState
typealias CallState = TaskState

/**
 * State for CallFunction task (function call).
 *
 * CallFunction is a control-flow task that navigates into a function's node tree.
 * The `entered` flag tracks whether we've descended into the function body.
 *
 * State transitions:
 * 1. Enter: entered = false (haven't navigated to function yet)
 * 2. getNextNode(): returns function's do block (first entry)
 * 3. Re-enter from child: entered = true (function completed)
 * 4. getNextNode(): returns node.parent (exit to caller)
 */
@Serializable
data class CallFunctionState(
    override val startedAt: Instant = Clock.System.now(),
    /** Whether we've entered the function body */
    val entered: Boolean = false,
) : NodeState()
