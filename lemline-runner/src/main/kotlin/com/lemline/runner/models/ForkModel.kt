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
    /** Unique identifier for this fork execution */
    override val id: IDV7 = IDV7.random(),

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
     * Timestamp when the fork has completed
     * if compete=true: timestamp of the first branch completion
     * if compete=false: timestamp of the last branch completion
     */
    override var outboxCompletedAt: Instant? = null,

    /**
     * Timestamp when the fork has failed
     * if compete=true: timestamp of the last branch failure
     * if compete=false: timestamp of the first branch failure
     */
    var failedAt: Instant? = null,

    /** Id of the failure entity if the fork failed */
    var failureId: IDV7? = null
) : AwaitingCompletionModel()
