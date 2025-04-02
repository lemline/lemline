package com.lemline.runtime.messaging

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.runtime.json.Json
import com.lemline.runtime.json.toJackson
import com.lemline.runtime.sw.tasks.JsonPointer
import com.lemline.runtime.sw.tasks.NodeState
import com.lemline.runtime.sw.tasks.NodeState.Companion.RAW_INPUT
import com.lemline.runtime.sw.tasks.NodeState.Companion.STARTED_AT
import com.lemline.runtime.sw.tasks.NodeState.Companion.WORKFLOW_ID
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkflowMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(JsonPointer.root to NodeState(rawInput = JsonNodeFactory.instance.textNode("")).toJson()!!),
            position = JsonPointer.root
        )

        // When
        assertEquals(
            message.toJson(),
            "{\"n\":\"test-workflow\",\"v\":\"1.0.0\",\"s\":{\"\":\"{\\\"inp\\\":\\\"\\\"}\"},\"p\":\"\"}"
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(JsonPointer.root to NodeState(rawInput = JsonNodeFactory.instance.textNode("")).toJson()!!),
            position = JsonPointer.root
        )

        // When
        val jsonString = message.toJson()
        assertEquals(message, WorkflowMessage.fromJson(jsonString))
    }

    @Test
    fun `should serialize and deserialize WorkflowMessage`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(
                JsonPointer.root to NodeState(
                    workflowId = "test-id",
                    rawInput = buildJsonObject { put("test", "value") }.toJackson(),
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!
            ),
            position = JsonPointer.root
        )

        // When
        val json = Json.toJson(message)
        val deserialized = Json.fromJson<WorkflowMessage>(json)

        // Then
        assertEquals(message.name, deserialized.name)
        assertEquals(message.version, deserialized.version)
        assertEquals(message.position, deserialized.position)
        assertEquals(1, deserialized.states.size)
        assertTrue(deserialized.states.containsKey(JsonPointer.root))
    }

    @Test
    fun `should create new instance with correct initial state`() {
        // Given
        val name = "test-workflow"
        val version = "1.0.0"
        val id = "test-id"
        val input = buildJsonObject {
            put("key1", "value1")
            put("key2", "value2")
        }

        // When
        val message = WorkflowMessage.newInstance(name, version, id, input)

        // Then
        assertEquals(name, message.name)
        assertEquals(version, message.version)
        assertEquals(JsonPointer.root, message.position)
        assertEquals(1, message.states.size)

        val rootState = message.states[JsonPointer.root] as ObjectNode
        assertEquals(id, rootState.get(WORKFLOW_ID).asText())
        assertEquals(input.toJackson(), rootState.get(RAW_INPUT))
        assertNotNull(rootState.get(STARTED_AT))
    }

    @Test
    fun `should handle multiple states in message`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(
                JsonPointer.root to NodeState(
                    workflowId = "test-id",
                    rawInput = buildJsonObject { put("test", "value") }.toJackson(),
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!,
                JsonPointer("/tasks/0") to NodeState(
                    workflowId = "test-id",
                    rawInput = buildJsonObject { put("task", "value") }.toJackson(),
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!
            ),
            position = JsonPointer("/tasks/0")
        )

        // When
        val json = Json.toJson(message)
        val deserialized = Json.fromJson<WorkflowMessage>(json)

        // Then
        assertEquals(2, deserialized.states.size)
        assertTrue(deserialized.states.containsKey(JsonPointer.root))
        assertTrue(deserialized.states.containsKey(JsonPointer("/tasks/0")))
        assertEquals(JsonPointer("/tasks/0"), deserialized.position)
    }

    @Test
    fun `should handle complex nested states`() {
        // Given
        val message = WorkflowMessage(
            name = "test-workflow",
            version = "1.0.0",
            states = mapOf(
                JsonPointer.root to NodeState(
                    workflowId = "test-id",
                    rawInput = buildJsonObject {
                        put("level1", "value1")
                        putJsonObject("level2") {
                            put("level3", "value3")
                        }
                    }.toJackson(),
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!
            ),
            position = JsonPointer.root
        )

        // When
        val json = Json.toJson(message)
        val deserialized = Json.fromJson<WorkflowMessage>(json)
        // Then
        val rootState = deserialized.states[JsonPointer.root] as ObjectNode
        val rawInput = rootState.get(RAW_INPUT) as ObjectNode
        assertEquals("value1", rawInput.get("level1").asText())
        assertEquals("value3", rawInput.get("level2").get("level3").asText())
    }
} 