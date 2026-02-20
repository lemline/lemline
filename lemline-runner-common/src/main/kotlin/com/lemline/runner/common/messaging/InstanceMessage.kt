// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging
import com.lemline.common.values.WithDefiniteWorkflowInfo
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
data class InstanceMessage<S : WorkflowState>(
    /**
     * Description of the workflow instance
     */
    override val workflowInfo: WorkflowInfo,
    /**
     * Description of the workflow instance
     */
    val workflowState: S,
) : WithDefiniteWorkflowInfo, TransportSerializable {
    override fun toTransportPayload(): String {
        return InstanceMessageCodec.toTransportPayload(this)
    }

    val workflowId get() = workflowState.workflowId
    val hasWaitingParent get() = workflowState.hasWaitingParent

    companion object {
        inline fun <reified S : WorkflowState> fromTransportPayload(payload: String): InstanceMessage<S> {
            return InstanceMessageCodec.fromTransportPayloadAs(payload)
        }

        inline fun <reified S : WorkflowState> fromMessage(message: Message<String>): InstanceMessage<S> =
            fromTransportPayload<S>(message.payload)
    }
}
