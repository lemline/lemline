// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class WorkflowModelMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val messageBody = MessageBody(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
            position = NodePosition.root,
        )

        // When
        assertEquals(
            """{"n":"test-workflow","v":"1.0.0","s":{"":{"inp":""}},"p":""}""",
            messageBody.toJsonString(),
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val messageBody = MessageBody(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
            position = NodePosition.root,
        )

        // When
        val jsonString = messageBody.toJsonString()
        assertEquals(messageBody, MessageBody.fromJsonString(jsonString))
    }

    @Test
    fun `should serialize and deserialize WorkflowMessage`() {
        // Given
        val messageBody = MessageBody(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = "test-id",
                    rawInput = JsonObject(mapOf("test" to JsonPrimitive("value"))),
                    startedAt = Instant.now(),
                ),
            ),
            position = NodePosition.root,
        )

        // When
        val json = LemlineJson.encodeToString(messageBody)
        val deserialized = LemlineJson.decodeFromString<MessageBody>(json)

        // Then
        assertEquals(messageBody, deserialized)
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
        val messageBody = MessageBody.newInstance(id, name, version, input)

        // Then
        val expectedStates = mapOf(
            NodePosition.root to NodeState(
                workflowId = id,
                rawInput = input,
                startedAt = messageBody.states[NodePosition.root]!!.startedAt,
            ),
        )

        assertEquals(name, messageBody.name)
        assertEquals(version, messageBody.version)
        assertEquals(expectedStates, messageBody.states)
        assertEquals(NodePosition.root, messageBody.position)
    }
}
