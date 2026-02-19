// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.messages.internal.v1.InternalMessageEnvelope
import com.lemline.messages.internal.v1.MessageMetadata
import com.lemline.messages.internal.v1.WorkflowInfoMessage
import com.lemline.messages.internal.v1.WorkflowStatePayload
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.wire.WireJsonAdapterFactory
import java.util.Base64
import java.time.Instant as JavaInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ExperimentalTime
object InstanceMessageCodec {
    private const val SCHEMA_VERSION = 1
    private const val MESSAGE_TYPE_COMMAND = "workflow.command"
    private const val MESSAGE_TYPE_EVENT = "workflow.event"

    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()
    private val databaseMoshi: Moshi by lazy {
        Moshi.Builder()
            .add(WireJsonAdapterFactory(writeIdentityValues = true))
            .build()
    }
    private val workflowStatePayloadJsonAdapter: JsonAdapter<WorkflowStatePayload> by lazy {
        databaseMoshi.adapter(WorkflowStatePayload::class.java)
    }

    fun toTransportPayload(message: InstanceMessage<out WorkflowState>): String {
        val bytes = InternalMessageEnvelope.ADAPTER.encode(toEnvelope(message))
        return base64Encoder.encodeToString(bytes)
    }

    fun fromTransportPayload(payload: String): InstanceMessage<WorkflowState> {
        val envelope = InternalMessageEnvelope.ADAPTER.decode(base64Decoder.decode(payload))
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
        return workflowStatePayloadJsonAdapter.toJson(proto)
    }

    fun workflowStateFromDbJson(payload: String): WorkflowState {
        val proto = workflowStatePayloadJsonAdapter.fromJson(payload)
            ?: error("Cannot decode workflow_state JSON payload")
        return WorkflowStateProtobufMapper.fromProto(proto)
    }

    internal fun toEnvelope(
        message: InstanceMessage<out WorkflowState>,
        emittedAt: Instant = Clock.System.now()
    ): InternalMessageEnvelope {
        val state = message.workflowState
        return InternalMessageEnvelope(
            message_type = when (state) {
                is WorkflowCommand -> MESSAGE_TYPE_COMMAND
                is WorkflowEvent -> MESSAGE_TYPE_EVENT
            },
            schema_version = SCHEMA_VERSION,
            workflow_info = WorkflowInfoMessage(
                namespace = message.workflowInfo.namespace.toString(),
                name = message.workflowInfo.name.toString(),
                version = message.workflowInfo.version.toString()
            ),
            metadata = MessageMetadata(
                workflow_id = message.workflowId.toString(),
                node_position = message.workflowState.nodePosition.toString(),
                emitted_at = emittedAt.toProtoInstant()
            ),
            command = when (state) {
                is WorkflowCommand -> WorkflowStateProtobufMapper.toCommandProto(state)
                is WorkflowEvent -> null
            },
            event = when (state) {
                is WorkflowCommand -> null
                is WorkflowEvent -> WorkflowStateProtobufMapper.toEventProto(state)
            }
        )
    }

    internal fun fromEnvelope(envelope: InternalMessageEnvelope): InstanceMessage<WorkflowState> {
        val state = envelope.command?.let { WorkflowStateProtobufMapper.fromCommandProto(it) }
            ?: envelope.event?.let { WorkflowStateProtobufMapper.fromEventProto(it) }
            ?: error("Envelope payload is not set")

        val workflowInfo = envelope.workflow_info?.toDomain()
            ?: error("Envelope workflow_info is not set")

        return InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = state,
        )
    }

    private fun WorkflowInfoMessage.toDomain(): WorkflowInfo =
        WorkflowInfo(
            namespace = WorkflowNamespace(namespace),
            name = WorkflowName(name),
            version = WorkflowVersion(version),
        )

    private fun Instant.toProtoInstant(): JavaInstant =
        JavaInstant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
}
