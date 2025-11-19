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
    /** Reference to parent fork by ID */
    val forkId: IDV7,

    /** Numeric index for ordering (0, 1, 2...) */
    val branchIndex: Int,

    /** Human-readable branch name from the workflow definition */
    val branchName: String,

    /** Serialized NodePosition indicating where this branch executes */
    val branchNodePosition: String,

    /** Current execution status of the branch (PENDING, RUNNING, COMPLETED, FAULTED) */
    val status: BranchStatus,

    /** Branch execution output as JSON, null until completed */
    val output: String?,

    /** Error details if branch execution failed */
    val error: String?,

    /** Timestamp when the branch completed execution */
    val completedAt: Instant?,

    /** Timestamp when the branch was created */
    val createdAt: Instant = Clock.System.now(),

    /** Timestamp when the branch was last updated */
    val updatedAt: Instant = createdAt
)
