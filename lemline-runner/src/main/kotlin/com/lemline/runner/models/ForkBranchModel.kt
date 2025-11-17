// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.states.BranchStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Database model for individual fork branch execution.
 * One row per branch, tracks branch state and output.
 */
@ExperimentalTime
data class ForkBranchModel(
    val forkId: IDV7,  // Reference to parent fork by ID
    val branchIndex: Int,  // Numeric index for ordering (0, 1, 2...)
    val branchName: String,  // Human-readable branch name
    val branchNodePosition: String,
    val status: BranchStatus,
    val output: String?,  // JSON, nullable until completed
    val error: String?,   // Error details if FAULTED
    val completedAt: Instant?,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt
)
