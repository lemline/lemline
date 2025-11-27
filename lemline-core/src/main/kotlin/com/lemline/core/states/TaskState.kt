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
typealias CallState = TaskState
typealias RunState = TaskState
typealias RaiseState = TaskState
typealias WaitState = TaskState
typealias SetState = TaskState
typealias ForkState = TaskState
typealias SwitchState = TaskState
