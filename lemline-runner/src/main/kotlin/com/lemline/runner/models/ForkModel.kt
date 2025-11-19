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
    val forkPosition: String,

    /** Whether branches compete (first to complete wins) or cooperate (all must complete) */
    val compete: Boolean,

    /** Number of branches in this fork */
    val branchCount: Int,

    /** Timestamp when all branches completed, null while awaiting */
    override var outboxCompletedAt: Instant? = null
) : AwaitingCompletionModel()
