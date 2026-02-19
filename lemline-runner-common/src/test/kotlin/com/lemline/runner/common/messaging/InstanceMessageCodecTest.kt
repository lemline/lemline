// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.messages.internal.v1.InternalMessageEnvelope
import java.util.Base64
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val envelope = InternalMessageEnvelope.ADAPTER.decode(Base64.getDecoder().decode(encoded))

        assertEquals(1, envelope.schema_version)
        assertEquals("workflow.command", envelope.message_type)
    }

    @Test
    fun `should round-trip workflow state through protojson for database`() {
        val commandState = commandMessage().workflowState
        val eventState = eventMessage().workflowState

        val commandJson = InstanceMessageCodec.workflowStateToDbJson(commandState)
        assertTrue(commandJson.contains("resumeWithCompletedTask"))
        assertFalse(commandJson.contains("_wirePayloadBase64"))

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

    @Test
    fun `db protojson should expose structured run workflow config keys`() {
        val json = InstanceMessageCodec.workflowStateToDbJson(runWorkflowEventMessage().workflowState)

        assertTrue(json.contains("\"runWorkflowStarted\""))
        assertTrue(json.contains("\"config\":{\"namespace\":\"child-ns\""))
        assertTrue(json.contains("\"name\":\"child-workflow\""))
        assertTrue(json.contains("\"version\":\"2.1.0\""))
        assertFalse(json.contains("configJson"))
    }

    @Test
    fun `should preserve listen correlation expect through transport payload`() {
        val message = listenEventMessage()

        val decoded = InstanceMessageCodec.fromTransportPayloadAs<WorkflowEvent>(
            InstanceMessageCodec.toTransportPayload(message)
        )

        val listenState = decoded.workflowState as WorkflowEvent.ListenStarted
        val correlation = listenState.config.filters.single().correlations?.get("orderId")
        assertEquals("ORD-54321", correlation?.expect)
        assertEquals(message, decoded)
    }

    @Test
    fun `should preserve listen correlation expect through db protojson`() {
        val state = listenEventMessage().workflowState

        val decoded = InstanceMessageCodec.workflowStateFromDbJson(
            InstanceMessageCodec.workflowStateToDbJson(state)
        ) as WorkflowEvent.ListenStarted

        val correlation = decoded.config.filters.single().correlations?.get("orderId")
        assertEquals("ORD-54321", correlation?.expect)
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

    private fun runWorkflowEventMessage(): InstanceMessage<WorkflowEvent> =
        InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowEvent.RunWorkflowStarted(
                nodeStack = nodeStack(),
                rawInput = JsonPrimitive("child-input"),
                config = RunWorkflowConfig(
                    namespace = WorkflowNamespace("child-ns"),
                    name = WorkflowName("child-workflow"),
                    version = WorkflowVersion("2.1.0"),
                    input = JsonPrimitive("payload"),
                    sync = true
                )
            )
        )

    private fun listenEventMessage(): InstanceMessage<WorkflowEvent> =
        InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowEvent.ListenStarted(
                nodeStack = nodeStack(),
                rawOutput = JsonPrimitive("listen"),
                config = ListenConfig(
                    strategy = ListenStrategy.ONE,
                    filters = listOf(
                        EventFilter(
                            type = "order.created",
                            correlations = mapOf(
                                "orderId" to CorrelationDef(
                                    from = "\${ .orderId }",
                                    expect = "ORD-54321"
                                )
                            )
                        )
                    ),
                    readAs = ListenAndReadAs.DATA,
                    correlationContext = buildJsonObject {
                        put("input", buildJsonObject { put("orderId", "ORD-54321") })
                    }
                )
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
