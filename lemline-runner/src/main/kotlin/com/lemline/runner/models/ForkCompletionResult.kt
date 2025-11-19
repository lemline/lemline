// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.states.TaskStates
import kotlin.time.ExperimentalTime

/**
 * Result of checking fork completion after a branch completes.
 * Returned by ForkWaitingRepository.recordBranchCompletion().
 */
@ExperimentalTime
data class ForkCompletionResult(
    /** Whether the fork has completed (all branches done in cooperative mode, or first branch done in compete mode) */
    val isComplete: Boolean,

    /** Number of branches that have completed execution */
    val completedCount: Int,

    /** Total number of branches in this fork */
    val branchCount: Int,

    /** Whether branches compete (first wins) or cooperate (all must complete) */
    val compete: Boolean,

    /** Merged task states from all completed branches */
    val taskStates: TaskStates,

    /** List of all branch execution records */
    val branches: List<ForkBranchModel>
)
