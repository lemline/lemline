// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.common.messaging

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.system.measureNanoTime
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class InstanceMessageCodecBenchmarkTest {

    @Test
    fun `protobuf transport payload should be smaller than legacy json for benchmark fixtures`() {
        benchmarkMessages().forEach { message ->
            val legacy = legacyEncode(message)
            val protobuf = InstanceMessageCodec.toTransportPayload(message)

            assertTrue(
                protobuf.length < legacy.length,
                "Expected protobuf transport payload to be smaller than legacy JSON. " +
                    "legacy=${legacy.length}B, protobuf=${protobuf.length}B"
            )
        }
    }

    @Test
    fun `protobuf codec should stay within latency budget versus legacy json`() {
        val message = benchmarkMessages().first()
        val iterations = 1_000

        // Warm-up
        repeat(200) {
            val legacy = legacyEncode(message)
            legacyDecode(legacy)
            val proto = InstanceMessageCodec.toTransportPayload(message)
            InstanceMessageCodec.fromTransportPayloadAs<WorkflowState>(proto)
        }

        val legacyTime = measureNanoTime {
            repeat(iterations) {
                val payload = legacyEncode(message)
                legacyDecode(payload)
            }
        }

        val protobufTime = measureNanoTime {
            repeat(iterations) {
                val payload = InstanceMessageCodec.toTransportPayload(message)
                InstanceMessageCodec.fromTransportPayloadAs<WorkflowState>(payload)
            }
        }

        val ratio = protobufTime.toDouble() / legacyTime.toDouble()
        assertTrue(
            ratio <= 3.0,
            "Protobuf latency budget exceeded. ratio=${ratio.roundTo(2)}x, " +
                "legacy=${legacyTime / 1_000_000}ms, protobuf=${protobufTime / 1_000_000}ms"
        )
    }

    private fun benchmarkMessages(): List<InstanceMessage<out WorkflowState>> =
        listOf(
            InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = WorkflowCommand.ResumeWithCompletedTask(
                    nodeStack = nodeStack(),
                    rawOutput = JsonArray((1..20).map { JsonPrimitive("item-$it") })
                )
            ),
            InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = WorkflowEvent.RunWorkflowStarted(
                    nodeStack = nodeStack(),
                    rawInput = JsonPrimitive("workflow-input"),
                    config = RunWorkflowConfig(
                        namespace = WorkflowNamespace("bench"),
                        name = WorkflowName("child-workflow"),
                        version = WorkflowVersion("1.2.3"),
                        input = JsonArray((1..12).map { JsonPrimitive("v$it") }),
                        sync = true
                    )
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

    @Suppress("UNCHECKED_CAST")
    private fun legacyEncode(message: InstanceMessage<out WorkflowState>): String =
        LemlineJson.json.encodeToString(
            legacySerializer,
            message as InstanceMessage<WorkflowState>
        )

    private fun legacyDecode(payload: String): InstanceMessage<WorkflowState> =
        LemlineJson.json.decodeFromString(legacySerializer, payload)

    private fun Double.roundTo(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToLong() / factor
    }

    private companion object {
        private val workflowInfo = WorkflowInfo(
            namespace = WorkflowNamespace("ns"),
            name = WorkflowName("wf"),
            version = WorkflowVersion("1.0.0")
        )
        private val taskPosition = NodePosition("/do/0/task")
        private val now = Instant.fromEpochSeconds(1735732800)
        @Suppress("UNCHECKED_CAST")
        private val legacySerializer =
            InstanceMessage.serializer(
                kotlinx.serialization.serializer<WorkflowState>()
            ) as KSerializer<InstanceMessage<WorkflowState>>
    }
}
