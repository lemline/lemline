// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.states.TaskStates
import kotlin.time.ExperimentalTime

/**
 * Result of checking fork completion after a branch completes.
 * Returned by ForkRepository.recordBranchCompletion().
 */
@ExperimentalTime
data class ForkCompletionResult(
    val isComplete: Boolean,
    val completedCount: Int,
    val branchCount: Int,
    val compete: Boolean,
    val taskStates: TaskStates,
    val branches: List<ForkBranchModel>
)
