// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Runtime state for ForkTask execution.
 *
 * This state tracks:
 * - Which branches are pending/running/completed/faulted
 * - Outputs from completed branches
 *
 * The state is minimal since most fork coordination happens through
 * WorkflowState.RunningFork at the orchestrator level.
 */
@Serializable
@ExperimentalTime
data class ForkTaskState(
    override val startedAt: Instant,
    val branchStates: Map<Int, BranchState>,  // Track each branch status
    val branchOutputs: Map<Int, JsonElement> = emptyMap() // Store outputs of completed branches
) : TaskState()

/**
 * Status of a branch within ForkTaskState.
 * Duplicates BranchStatus from WorkflowState but exists at task state level.
 */
@Serializable
enum class BranchState {
    PENDING,    // Not yet started
    RUNNING,    // Currently executing
    COMPLETED,  // Successfully finished
    FAULTED     // Failed
}
