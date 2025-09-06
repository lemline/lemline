// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class WorkflowsSerializationTest {

    @Test
    fun `WorkflowName serializes and deserializes`() {
        val name = WorkflowName("orders")
        val encoded = LemlineJson.encodeToString(name)
        assertEquals(encoded, "\"orders\"")
        val decoded = LemlineJson.decodeFromString<WorkflowName>(encoded)
        assertEquals(name, decoded)
    }

    @Test
    fun `WorkflowVersion serializes and deserializes`() {
        val version = WorkflowVersion("v1")
        val encoded = LemlineJson.encodeToString(version)
        assertEquals(encoded, "\"v1\"")
        val decoded = LemlineJson.decodeFromString<WorkflowVersion>(encoded)
        assertEquals(version, decoded)
    }

    @Test
    fun `WorkflowId serializes and deserializes`() {
        val id = WorkflowId.random()
        val encoded = LemlineJson.encodeToString(id)
        assertEquals(encoded, "\"$id\"")
        val decoded = LemlineJson.decodeFromString<WorkflowId>(encoded)
        assertEquals(id, decoded)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `NodeStates serializes and deserializes`() {
        val root = NodePosition.root
        val state = NodeState(rawInput = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("a"))))
        val states = NodeStates(mapOf(root to state))

        val encoded = LemlineJson.encodeToString(states)
        val decoded = LemlineJson.decodeFromString<NodeStates>(encoded)

        assertNotNull(decoded[root])
        assertEquals(state.attemptIndex, decoded[root]?.attemptIndex)
        assertEquals(state.childIndex, decoded[root]?.childIndex)
        assertEquals(state.rawInput, decoded[root]?.rawInput)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `Instance serializes and deserializes`() {
        val workflowState = WorkflowState(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("orders"),
            workflowVersion = WorkflowVersion("v1"),
            currentPosition = NodePosition.root,
            currentStates = NodeStates.new(JsonPrimitive("hello"))
        )

        val encoded = LemlineJson.encodeToString(workflowState)
        val decoded = LemlineJson.decodeFromString<WorkflowState>(encoded)
        assertEquals(workflowState.workflowName, decoded.workflowName)
        assertEquals(workflowState.workflowVersion, decoded.workflowVersion)
        assertEquals(workflowState.workflowId, decoded.workflowId)
        assertEquals(workflowState.currentPosition, decoded.currentPosition)
        assertNotNull(decoded.currentStates[NodePosition.root])
    }
}
