// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.google.protobuf.Timestamp
import com.google.protobuf.util.JsonFormat
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
import java.util.Base64
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
    private val protoJsonPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
    private val protoJsonParser = JsonFormat.parser().ignoringUnknownFields()

    fun toTransportPayload(message: InstanceMessage<out WorkflowState>): String {
        val bytes = toEnvelope(message).toByteArray()
        return base64Encoder.encodeToString(bytes)
    }

    fun fromTransportPayload(payload: String): InstanceMessage<WorkflowState> {
        val envelope = InternalMessageEnvelope.parseFrom(base64Decoder.decode(payload))
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

    fun workflowStateToDbJson(state: WorkflowState): String =
        protoJsonPrinter.print(WorkflowStateProtobufMapper.toProto(state))

    fun workflowStateFromDbJson(payload: String): WorkflowState {
        val builder = WorkflowStatePayload.newBuilder()
        protoJsonParser.merge(payload, builder)
        return WorkflowStateProtobufMapper.fromProto(builder.build())
    }

    internal fun toEnvelope(
        message: InstanceMessage<out WorkflowState>,
        emittedAt: Instant = Clock.System.now()
    ): InternalMessageEnvelope {
        val state = message.workflowState
        return InternalMessageEnvelope.newBuilder()
            .setMessageType(
                when (state) {
                    is WorkflowCommand -> MESSAGE_TYPE_COMMAND
                    is WorkflowEvent -> MESSAGE_TYPE_EVENT
                }
            )
            .setSchemaVersion(SCHEMA_VERSION)
            .setWorkflowInfo(
                WorkflowInfoMessage.newBuilder()
                    .setNamespace(message.workflowInfo.namespace.toString())
                    .setName(message.workflowInfo.name.toString())
                    .setVersion(message.workflowInfo.version.toString())
                    .build()
            )
            .setMetadata(
                MessageMetadata.newBuilder()
                    .setWorkflowId(message.workflowId.toString())
                    .setNodePosition(message.workflowState.nodePosition.toString())
                    .setEmittedAt(emittedAt.toProto())
                    .build()
            )
            .apply {
                when (state) {
                    is WorkflowCommand -> setCommand(WorkflowStateProtobufMapper.toCommandProto(state))
                    is WorkflowEvent -> setEvent(WorkflowStateProtobufMapper.toEventProto(state))
                }
            }
            .build()
    }

    internal fun fromEnvelope(envelope: InternalMessageEnvelope): InstanceMessage<WorkflowState> {
        val state = when (envelope.payloadCase) {
            InternalMessageEnvelope.PayloadCase.COMMAND -> WorkflowStateProtobufMapper.fromCommandProto(envelope.command)
            InternalMessageEnvelope.PayloadCase.EVENT -> WorkflowStateProtobufMapper.fromEventProto(envelope.event)
            InternalMessageEnvelope.PayloadCase.PAYLOAD_NOT_SET -> error("Envelope payload is not set")
            null -> error("Envelope payload is unknown")
        }

        val workflowInfo = envelope.workflowInfo.toDomain()

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

    private fun Instant.toProto(): Timestamp =
        Timestamp.newBuilder()
            .setSeconds(epochSeconds)
            .setNanos(nanosecondsOfSecond)
            .build()
}
