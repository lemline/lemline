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
import com.lemline.messages.internal.v1.InstanceMessageProto
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val envelope = InstanceMessageProto.ADAPTER.decode(Base64.getDecoder().decode(encoded))

        assertEquals(1, envelope.schema_version)
        assertTrue(envelope.state?.command != null)
    }

    @Test
    fun `should round-trip workflow state through protobuf db payload`() {
        val commandState = commandMessage().workflowState
        val eventState = eventMessage().workflowState

        val commandPayload = InstanceMessageCodec.workflowStateToDbJson(commandState)
        assertTrue(commandPayload.startsWith("pb64:"))
        assertFalse(commandPayload.contains("resumeWithCompletedTask"))

        assertEquals(
            commandState,
            InstanceMessageCodec.workflowStateFromDbJson(
                commandPayload
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
    fun `should reject non protobuf db payload`() {
        val legacyJson = """{"state":"legacy-json"}"""

        assertFailsWith<IllegalArgumentException> {
            InstanceMessageCodec.workflowStateFromDbJson(legacyJson)
        }
    }

    @Test
    fun `db protobuf payload should preserve run workflow config`() {
        val payload = InstanceMessageCodec.workflowStateToDbJson(runWorkflowEventMessage().workflowState)

        assertTrue(payload.startsWith("pb64:"))
        assertFalse(payload.contains("child-workflow"))

        val decoded = InstanceMessageCodec.workflowStateFromDbJson(payload) as WorkflowEvent.RunWorkflowStarted
        assertEquals("child-ns", decoded.config.namespace.toString())
        assertEquals("child-workflow", decoded.config.name.toString())
        assertEquals("2.1.0", decoded.config.version.toString())
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
    fun `should preserve listen correlation expect through db payload`() {
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
