// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@ExperimentalTime
internal class InstanceMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val workflowId = UUID.randomUUID()
        val parentId = UUID.randomUUID()

        val instanceMessage = InstanceMessage.fromObjects(
            workflowId = workflowId,
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            currentPosition = NodePosition.root,
            currentStates = NodeStates(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            parentId = parentId
        )

        // When
        Assertions.assertEquals(
            """{"i":"$workflowId","n":"test-workflow","v":"1.0.0","p":"","s":{"":{"inp":""}},"w":"$parentId"}""",
            instanceMessage.toJsonString(),
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val instanceMessage = InstanceMessage.fromObjects(
            workflowId = UUID.randomUUID(),
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            currentPosition = NodePosition.root,
            currentStates = NodeStates(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            parentId = UUID.randomUUID(),
        )

        // When
        Assertions.assertEquals(instanceMessage, InstanceMessage.fromJsonString(instanceMessage.toJsonString()))
    }

    @Test
    fun `should serialize and deserialize MessageBody`() {
        // Given
        val original = InstanceMessage.fromObjects(
            workflowId = UUID.randomUUID(),
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            currentPosition = NodePosition.root,
            currentStates = NodeStates(
                mapOf(
                    NodePosition.root to NodeState(
                        rawInput = JsonObject(mapOf("test" to JsonPrimitive("value"))),
                        startedAt = Clock.System.now(),
                    )
                ),
            ),
            parentId = UUID.randomUUID(),
        )

        // When
        val json = LemlineJson.encodeToString(original)
        val deserialized = LemlineJson.decodeFromString<InstanceMessage>(json)

        // Then
        Assertions.assertEquals(original, deserialized)
    }

    @Test
    fun `should create new instance with correct initial state`() {
        // Given
        val id = UUID.randomUUID()
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
        val expectedStates = NodeStates(
            mapOf(
                NodePosition.root to NodeState(
                    rawInput = input,
                    startedAt = instanceMessage.nodeStates.parsed[NodePosition.root]!!.startedAt,
                ),
            )
        )

        Assertions.assertEquals(name, instanceMessage.workflowName)
        Assertions.assertEquals(version, instanceMessage.workflowVersion)
        Assertions.assertEquals(expectedStates, instanceMessage.nodeStates.parsed)
        Assertions.assertEquals(NodePosition.root, instanceMessage.workflowPosition.parsed)
    }
}
