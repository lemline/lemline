// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("p") // <- type discriminator for polymorphic serialization
data class ParentWaitingModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    val instanceMessage: InstanceMessage<WorkflowEvent.RunWorkflowStarted>
) : InstanceModel {

    override val workflowInfo: WorkflowInfo get() = instanceMessage.workflowInfo

    override val workflowState: WorkflowState get() = instanceMessage.workflowState

    override val parentId: IDV7? get() = instanceMessage.parentId

    // Needed by tests
    companion object
}
