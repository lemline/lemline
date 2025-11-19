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
    override val id: IDV7 = IDV7.random(),
    override val instanceMessage: InstanceMessage<WorkflowEvent.ForkStarted>,
    val forkPosition: String,  // Serialized NodePosition
    val compete: Boolean,
    val branchCount: Int,
    override var outboxCompletedAt: Instant? = null
) : AwaitingCompletionModel()
