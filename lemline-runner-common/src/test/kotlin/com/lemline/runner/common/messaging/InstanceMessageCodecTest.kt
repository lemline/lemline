// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.messages.internal.v1.InternalMessageEnvelope
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class InstanceMessageCodecTest {

    @Test
    fun `should round-trip command through protobuf transport payload`() {
        val message = commandMessage()
        val encoded = InstanceMessageCodec.toTransportPayload(message)
        val decoded = InstanceMessageCodec.fromTransportPayloadAs<WorkflowCommand>(encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun `should round-trip event through protobuf transport payload`() {
        val message = eventMessage()
        val encoded = InstanceMessageCodec.toTransportPayload(message)
        val decoded = InstanceMessageCodec.fromTransportPayloadAs<WorkflowEvent>(encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun `should expose schema metadata in envelope`() {
        val encoded = InstanceMessageCodec.toTransportPayload(commandMessage())
        val envelope = InternalMessageEnvelope.parseFrom(Base64.getDecoder().decode(encoded))

        assertEquals(1, envelope.schemaVersion)
        assertEquals("workflow.command", envelope.messageType)
    }

    @Test
    fun `should round-trip workflow state through protojson for database`() {
        val commandState = commandMessage().workflowState
        val eventState = eventMessage().workflowState

        val commandJson = InstanceMessageCodec.workflowStateToDbJson(commandState)
        assertTrue(commandJson.contains("resumeWithCompletedTask"))

        assertEquals(
            commandState,
            InstanceMessageCodec.workflowStateFromDbJson(
                commandJson
            )
        )

        assertEquals(
            eventState,
            InstanceMessageCodec.workflowStateFromDbJson(
                InstanceMessageCodec.workflowStateToDbJson(eventState)
            )
        )
    }

    @Test
    fun `should ignore unknown fields in protojson database payload`() {
        val state = commandMessage().workflowState
        val json = InstanceMessageCodec.workflowStateToDbJson(state)
        val payloadWithUnknown = json.replaceFirst("{", """{"futureField":"ignored",""")

        val decoded = InstanceMessageCodec.workflowStateFromDbJson(payloadWithUnknown)

        assertEquals(state, decoded)
    }

    private fun commandMessage(): InstanceMessage<WorkflowCommand> =
        InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowCommand.ResumeWithCompletedTask(
                nodeStack = nodeStack(),
                rawOutput = JsonPrimitive("ok")
            )
        )

    private fun eventMessage(): InstanceMessage<WorkflowEvent> =
        InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowEvent.WaitStarted(
                nodeStack = nodeStack(),
                rawOutput = JsonPrimitive("wait"),
                config = WaitConfig(waitUntil = now)
            )
        )

    private fun nodeStack(): NodeStack {
        val root = StackFrame(
            position = NodePosition.root,
            state = RootState(
                startedAt = now,
                workflowId = WorkflowId.random(),
                workflowInput = JsonPrimitive("input")
            ),
            counter = 1
        )

        val task = StackFrame(
            position = taskPosition,
            state = TaskState(startedAt = now),
            counter = 2
        )
        return NodeStack.fromFrames(listOf(root, task))
    }

    private companion object {
        private val workflowInfo = WorkflowInfo(
            namespace = WorkflowNamespace("ns"),
            name = WorkflowName("wf"),
            version = WorkflowVersion("1.0.0")
        )
        private val taskPosition = NodePosition("/do/0/task")
        private val now = Instant.fromEpochSeconds(1735732800)
    }
}
