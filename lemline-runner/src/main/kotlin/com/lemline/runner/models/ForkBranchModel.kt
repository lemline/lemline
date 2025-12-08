// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Database model for individual fork branch execution.
 * One row per branch, tracks branch state and output.
 */
@OptIn(ExperimentalSerializationApi::class)
@ExperimentalTime
data class ForkBranchModel(
    /** Reference to parent fork by ID */
    val forkId: IDV7,

    /** Human-readable branch name from the workflow definition */
    val name: String,

    /** Branch execution output as JSON, null until completed */
    var output: String?,

    /** Timestamp when the branch completed */
    override var completedAt: Instant?,

    /** Timestamp when the branch failed */
    var failedAt: Instant?,

    /** High-level categorization of the failure reason, null if no failure */
    var errorReason: String? = null,

    /** Fully qualified class name of the exception that caused the failure, null if no failure */
    var errorClass: String? = null,

    /** Error message from the exception, null if no failure or no message */
    var errorMessage: String? = null,

    /** Full stack trace of the exception for debugging, null if no failure */
    var errorStackTrace: String? = null,

    ) : WithCompletedAt {
    companion object
}
