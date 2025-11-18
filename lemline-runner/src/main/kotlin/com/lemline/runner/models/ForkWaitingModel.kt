// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Database model for fork metadata.
 * Stores fork configuration and parent workflow state for async parallel execution.
 *
 * Uses multiple-rows approach (one metadata row + one row per branch)
 * for better concurrency and observability.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@Serializable
@SerialName("fork")
data class ForkWaitingModel(
    @SerialName("id")
    override val id: IDV7 = IDV7.random(),

    @SerialName("i")
    val instanceMessage: InstanceMessage<WorkflowEvent.ForkStarted>,

    @SerialName("fp")
    val forkPosition: String,  // Serialized NodePosition

    @SerialName("c")
    val compete: Boolean,

    @SerialName("bc")
    val branchCount: Int
) : InstanceModel {

    override val workflowInfo: WorkflowInfo get() = instanceMessage.workflowInfo

    override val workflowState: WorkflowState get() = instanceMessage.workflowState

    override val parentId: IDV7? get() = instanceMessage.parentId
}
