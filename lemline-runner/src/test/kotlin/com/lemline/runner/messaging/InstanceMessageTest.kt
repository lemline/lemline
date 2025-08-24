// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.WorkflowState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
internal class InstanceMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val instanceMessage = InstanceMessage.fromObjects(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            scheduleId = "test-schedule-id",
            parentId = "test-parent-id",
        )

        // When
        assertEquals(
            """{"i":"test-id","n":"test-workflow","v":"1.0.0","p":"","s":{"":{"inp":""}}}""",
            instanceMessage.payload,
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val instanceMessage = InstanceMessage.fromObjects(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            scheduleId = "test-schedule-id",
            parentId = "test-parent-id",
        )

        // When
        assertEquals(instanceMessage, InstanceMessage.fromJsonString(instanceMessage.payload))
    }

    @Test
    fun `should serialize and deserialize MessageBody`() {
        // Given
        val original = InstanceMessage.fromObjects(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(
                mapOf(
                    NodePosition.root to NodeState(
                        rawInput = JsonObject(mapOf("test" to JsonPrimitive("value"))),
                        startedAt = Clock.System.now(),
                    )
                ),
            ),
            scheduleId = "test-schedule-id",
            parentId = "test-parent-id",
        )

        // When
        val json = LemlineJson.encodeToString(original)
        val deserialized = LemlineJson.decodeFromString<InstanceMessage>(json)

        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should create new instance with correct initial state`() {
        // Given
        val id = "test-id"
        val name = "test-workflow"
        val version = "1.0.0"
        val input = JsonObject(
            mapOf(
                "key1" to JsonPrimitive("value1"),
                "key2" to JsonPrimitive("value2"),
            ),
        )

        // When
        val instanceMessage = InstanceMessage.forNewWorkflow(id, name, version, input)

        // Then
        val expectedStates = WorkflowState(
            mapOf(
                NodePosition.root to NodeState(
                    rawInput = input,
                    startedAt = instanceMessage.workflowState.parsed[NodePosition.root]!!.startedAt,
                ),
            )
        )

        assertEquals(name, instanceMessage.workflowName)
        assertEquals(version, instanceMessage.workflowVersion)
        assertEquals(expectedStates, instanceMessage.workflowState.parsed)
        assertEquals(NodePosition.root, instanceMessage.workflowPosition.parsed)
    }
}
