// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import com.lemline.common.json.JsonSerializable
import com.lemline.common.values.WithDefiniteWorkflowInfo
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
@Serializable
data class InstanceMessage<S : WorkflowState>(
    /**
     * Description of the workflow instance
     */
    @SerialName("i") override val workflowInfo: WorkflowInfo,

    /**
     * Description of the workflow instance
     */
    @SerialName("s") val workflowState: S,

    ) : WithDefiniteWorkflowInfo, JsonSerializable {

    override fun toJsonString(): String {
        return InstanceMessageCodec.toTransportPayload(this)
    }

    val workflowId get() = workflowState.workflowId

    val hasWaitingParent get() = workflowState.hasWaitingParent

    companion object {

        inline fun <reified S : WorkflowState> fromJsonString(jsonString: String): InstanceMessage<S> {
            return InstanceMessageCodec.fromTransportPayloadAs(jsonString)
        }

        inline fun <reified S : WorkflowState> fromMessage(message: Message<String>): InstanceMessage<S> =
            fromJsonString<S>(message.payload)
    }
}
