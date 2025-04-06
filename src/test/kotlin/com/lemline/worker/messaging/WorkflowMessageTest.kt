package com.lemline.worker.messaging

import com.lemline.common.json.Json
import com.lemline.sw.nodes.NodePosition
import com.lemline.sw.nodes.NodeState
import com.lemline.sw.set
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkflowMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive(""))),
            position = NodePosition.root
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
            position = NodePosition.root
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
                    rawInput = Json.jsonObject.set("test", "value"),
                    startedAt = Instant.now()
                )
            ),
            position = NodePosition.root
        )

        // When
        val json = Json.encodeToString(message)
        val deserialized = Json.decodeFromString<WorkflowMessage>(json)

        // Then
        assertEquals(message, deserialized)
    }

    @Test
    fun `should create new instance with correct initial state`() {
        // Given
        val name = "test-workflow"
        val version = "1.0.0"
        val id = "test-id"
        val input = Json.jsonObject
            .set("key1", "value1")
            .set("key2", "value2")

        // When
        val message = WorkflowMessage.newInstance(name, version, id, input)

        // Then
        val expected = WorkflowMessage(
            name = name,
            version = version,
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = id,
                    rawInput = input,
                    startedAt = Instant.now()
                )
            ),
            position = NodePosition.root
        )

        assertEquals(
            expected,
            message
        )
    }
} 