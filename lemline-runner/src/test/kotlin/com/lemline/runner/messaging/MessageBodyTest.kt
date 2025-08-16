// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MessageBodyTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val messageBody = MessageBody(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
        )

        // When
        assertEquals(
            """{"i":"test-id","n":"test-workflow","v":"1.0.0","s":{"":{"inp":""}},"p":""}""",
            messageBody.jsonString,
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val messageBody = MessageBody(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
        )

        // When
        assertEquals(messageBody, MessageBody.fromJsonString(messageBody.jsonString))
    }

    @Test
    fun `should serialize and deserialize MessageBody`() {
        // Given
        val messageBody = MessageBody(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = mapOf(
                NodePosition.root to NodeState(
                    rawInput = JsonObject(mapOf("test" to JsonPrimitive("value"))),
                    startedAt = Instant.now(),
                ),
            ),
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
        val messageBody = MessageBody.newInstance(id, name, version, input)

        // Then
        val expectedStates = mapOf(
            NodePosition.root to NodeState(
                rawInput = input,
                startedAt = messageBody._workflowStateStr[NodePosition.root]!!.startedAt,
            ),
        )

        assertEquals(name, messageBody.workflowName)
        assertEquals(version, messageBody.workflowVersion)
        assertEquals(expectedStates, messageBody._workflowStateStr)
        assertEquals(NodePosition.root, messageBody._workflowPositionStr)
    }
}
