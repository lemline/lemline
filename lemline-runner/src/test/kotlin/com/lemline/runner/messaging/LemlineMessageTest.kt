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
internal class LemlineMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val lemlineMessage = LemlineMessage.fromObjects(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            isScheduledAfter = false
        )

        // When
        assertEquals(
            """{"i":"test-id","n":"test-workflow","v":"1.0.0","p":"","s":{"":{"inp":""}}}""",
            lemlineMessage.jsonString,
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val lemlineMessage = LemlineMessage.fromObjects(
            workflowId = "test-id",
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            isScheduledAfter = false
        )

        // When
        assertEquals(lemlineMessage, LemlineMessage.fromJsonString(lemlineMessage.jsonString))
    }

    @Test
    fun `should serialize and deserialize MessageBody`() {
        // Given
        val original = LemlineMessage.fromObjects(
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
            isScheduledAfter = false
        )

        // When
        val json = LemlineJson.encodeToString(original)
        val deserialized = LemlineJson.decodeFromString<LemlineMessage>(json)

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
        val lemlineMessage = LemlineMessage.create(id, name, version, input)

        // Then
        val expectedStates = WorkflowState(
            mapOf(
                NodePosition.root to NodeState(
                    rawInput = input,
                    startedAt = lemlineMessage.workflowState.parsed[NodePosition.root]!!.startedAt,
                ),
            )
        )

        assertEquals(name, lemlineMessage.workflowName)
        assertEquals(version, lemlineMessage.workflowVersion)
        assertEquals(expectedStates, lemlineMessage.workflowState.parsed)
        assertEquals(NodePosition.root, lemlineMessage.workflowPosition.parsed)
    }
}
