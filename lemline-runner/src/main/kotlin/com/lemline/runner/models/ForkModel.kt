// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Database model for fork metadata.
 * Stores fork configuration and parent workflow state for async parallel execution.
 *
 * Uses multiple-rows approach (one metadata row + one row per branch)
 * for better concurrency and observability.
 */
@ExperimentalTime
@ExperimentalSerializationApi
data class ForkModel(
    /** Unique identifier for this fork execution - must be derived from position + step for idempotency */
    override val id: IDV7,

    /** Parent workflow state when the fork started */
    override val instanceMessage: InstanceMessage<WorkflowEvent.ForkStarted>,

    /** Serialized NodePosition indicating where the fork is in the workflow */
    val position: String,

    /** Whether branches compete (true, first to complete wins) or cooperate (false, all must complete) */
    val compete: Boolean,

    /**
     * Assembled output from completed branches (JSON string)
     * - In compete mode: output from the first completed branch
     * - In cooperate mode: JSON array of outputs from all branches
     * Null until fork completes
     */
    var output: String? = null,

    /**
     * Timestamp when the fork has failed
     * if compete=true: timestamp of the last branch failure
     * if compete=false: timestamp of the first branch failure
     */
    var failedAt: Instant? = null,

    /** High-level categorization of the failure reason, null if no failure */
    var errorReason: String? = null,

    /** Fully qualified class name of the exception that caused the failure, null if no failure */
    var errorClass: String? = null,

    /** Error message from the exception, null if no failure or no message */
    var errorMessage: String? = null,

    /** Full stack trace of the exception for debugging, null if no failure */
    var errorStackTrace: String? = null

) : WithId, WithInstanceMessage, WithCompletedAt, WithCleanup {

    /** Timestamp when the fork completed successfully */
    override var completedAt: Instant? = null


    /** Timestamp after which this entity can be deleted, set when the fork completes or fails */
    override var cleanupAfter: Instant? = null

    companion object
}
