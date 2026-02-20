// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowState
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.messages.internal.v1.InstanceMessageProto
import com.lemline.messages.internal.v1.WorkflowInfoProto
import com.lemline.messages.internal.v1.WorkflowStateProto
import java.util.*
import kotlin.time.ExperimentalTime

object InstanceMessageCodec {
    private const val SCHEMA_VERSION = 1
    private const val DB_PROTOBUF_BASE64_PREFIX = "pb64:"

    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    fun toTransportPayload(message: InstanceMessage<out WorkflowState>): String {
        val bytes = InstanceMessageProto.ADAPTER.encode(toEnvelope(message))
        return base64Encoder.encodeToString(bytes)
    }

    fun fromTransportPayload(payload: String): InstanceMessage<WorkflowState> {
        val envelope = InstanceMessageProto.ADAPTER.decode(base64Decoder.decode(payload))
        return fromEnvelope(envelope)
    }

    inline fun <reified S : WorkflowState> fromTransportPayloadAs(payload: String): InstanceMessage<S> {
        val message = fromTransportPayload(payload)
        val state = message.workflowState
        require(state is S) {
            "Decoded workflow state type mismatch. Expected ${S::class.qualifiedName}, got ${state::class.qualifiedName}"
        }
        @Suppress("UNCHECKED_CAST")
        return message as InstanceMessage<S>
    }

    fun workflowStateToDbJson(state: WorkflowState): String {
        val proto = WorkflowStateProtobufMapper.toProto(state)
        val payload = base64Encoder.encodeToString(WorkflowStateProto.ADAPTER.encode(proto))
        return DB_PROTOBUF_BASE64_PREFIX + payload
    }

    fun workflowStateFromDbJson(payload: String): WorkflowState {
        require(payload.startsWith(DB_PROTOBUF_BASE64_PREFIX)) {
            "Invalid workflow_state format. Expected '$DB_PROTOBUF_BASE64_PREFIX' prefix"
        }
        val base64Payload = payload.removePrefix(DB_PROTOBUF_BASE64_PREFIX)
        val proto = WorkflowStateProto.ADAPTER.decode(base64Decoder.decode(base64Payload))
        return WorkflowStateProtobufMapper.fromProto(proto)
    }

    internal fun toEnvelope(message: InstanceMessage<out WorkflowState>): InstanceMessageProto {
        val workflowState = message.workflowState
        return InstanceMessageProto(
            schema_version = SCHEMA_VERSION,
            workflow_info = WorkflowInfoProto(
                namespace = message.workflowInfo.namespace.toString(),
                name = message.workflowInfo.name.toString(),
                version = message.workflowInfo.version.toString()
            ),
            state = WorkflowStateProtobufMapper.toProto(workflowState)
        )
    }

    internal fun fromEnvelope(envelope: InstanceMessageProto): InstanceMessage<WorkflowState> {
        val state = envelope.state?.let { WorkflowStateProtobufMapper.fromProto(it) }
            ?: error("Envelope state is not set")

        val workflowInfo = envelope.workflow_info?.toDomain()
            ?: error("Envelope workflow_info is not set")

        return InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = state,
        )
    }

    private fun WorkflowInfoProto.toDomain(): WorkflowInfo =
        WorkflowInfo(
            namespace = WorkflowNamespace(namespace),
            name = WorkflowName(name),
            version = WorkflowVersion(version),
        )
}
