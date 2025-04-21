// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.messaging

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkflowModelMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
            position = NodePosition.root,
        )

        // When
        assertEquals(
            """{"n":"test-workflow","v":"1.0.0","s":{"":{"inp":""}},"p":""}""",
            message.toJsonString(),
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
            position = NodePosition.root,
        )

        // When
        val jsonString = message.toJsonString()
        assertEquals(message, WorkflowMessage.fromJsonString(jsonString))
    }

    @Test
    fun `should serialize and deserialize WorkflowMessage`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = "test-id",
                    rawInput = JsonObject(mapOf("test" to JsonPrimitive("value"))),
                    startedAt = Clock.System.now(),
                ),
            ),
            position = NodePosition.root,
        )

        // When
        val json = LemlineJson.encodeToString(message)
        val deserialized = LemlineJson.decodeFromString<WorkflowMessage>(json)

        // Then
        assertEquals(message, deserialized)
    }

    @Test
    fun `should create new instance with correct initial state`() {
        // Given
        val name = "test-workflow"
        val version = "1.0.0"
        val id = "test-id"
        val input = JsonObject(
            mapOf(
                "key1" to JsonPrimitive("value1"),
                "key2" to JsonPrimitive("value2"),
            ),
        )

        // When
        val message = WorkflowMessage.newInstance(name, version, id, input)

        // Then
        val expectedStates = mapOf(
            NodePosition.root to NodeState(
                workflowId = id,
                rawInput = input,
                startedAt = message.states[NodePosition.root]!!.startedAt,
            ),
        )

        assertEquals(name, message.name)
        assertEquals(version, message.version)
        assertEquals(expectedStates, message.states)
        assertEquals(NodePosition.root, message.position)
    }
}
