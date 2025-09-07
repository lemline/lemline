// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTime::class)
class WorkflowStateTest {

    @Test
    fun `WorkflowState serializes and deserializes`() {
        val workflowState = WorkflowState.random()

        val encoded = workflowState.toJsonString()
        val decoded = WorkflowState.fromJsonString(encoded)

        assertEquals(workflowState, decoded)
    }

    @Test
    fun `new() should initialize root position and initial node state`() {
        val input = JsonPrimitive("in")
        val ws = WorkflowState.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("orders"),
            workflowVersion = WorkflowVersion("v1"),
            input = input,
        )

        assertEquals(NodePosition.root, ws.currentPosition)
        val rootState = ws.currentStates[NodePosition.root]
        assertNotNull(rootState)
        assertEquals(input, rootState.rawInput)
        assertNull(rootState.rawOutput)
        assertNotNull(rootState.startedAt) // startedAt is set by NodeStates.new
    }

    @Test
    fun `setCurrentTaskOutput() should mutate current state's rawOutput`() {
        val ws = WorkflowState.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("orders"),
            workflowVersion = WorkflowVersion("v1"),
            input = JsonPrimitive("in"),
        )
        val out: JsonElement = JsonPrimitive("out")
        ws.setCurrentTaskOutput(out)
        assertEquals(out, ws.currentStates[NodePosition.root]?.rawOutput)
    }

    @Test
    fun `duplicate() should copy the state with a new id only`() {
        val original = WorkflowState.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("orders"),
            workflowVersion = WorkflowVersion("v1"),
            input = JsonPrimitive("in"),
        )
        val newId = WorkflowId.random()
        val dup = original.duplicate(newId)

        assertEquals(newId, dup.workflowId)
        assertEquals(original.workflowName, dup.workflowName)
        assertEquals(original.workflowVersion, dup.workflowVersion)
        assertEquals(original.currentPosition, dup.currentPosition)
        // NodeStates is a value class around a Map -> content equality should hold
        assertNotNull(dup.currentStates[NodePosition.root])
        assertEquals(
            original.currentStates[NodePosition.root]?.rawInput,
            dup.currentStates[NodePosition.root]?.rawInput
        )
    }

    @Test
    fun `serialization uses compact field names for backward compatibility`() {
        val ws = WorkflowState.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("orders"),
            workflowVersion = WorkflowVersion("v1"),
            input = JsonPrimitive("hello"),
        )
        val json = LemlineJson.encodeToElement(ws)
        // Top-level keys must be short names: i, n, v, p, s
        require(json is JsonObject)
        val keys = json.keys
        assertTrue("i" in keys)
        assertTrue("n" in keys)
        assertTrue("v" in keys)
        assertTrue("p" in keys)
        assertTrue("s" in keys)
        // Ensure no long names accidentally appear
        assertTrue("workflowId" !in keys)
        assertTrue("workflowName" !in keys)
        assertTrue("workflowVersion" !in keys)
        assertTrue("currentPosition" !in keys)
        assertTrue("currentStates" !in keys)
    }

    @Test
    fun `can deserialize legacy compact json (backward compatibility)`() {
        // Build a legacy-compatible JSON string using compact field names and compact NodeState keys.
        val legacyId = WorkflowId.random().toString()
        val legacyElement = kotlinx.serialization.json.buildJsonObject {
            put("i", JsonPrimitive(legacyId))
            put("n", JsonPrimitive("orders"))
            put("v", JsonPrimitive("v1"))
            put("p", JsonPrimitive(""))
            val innerState = kotlinx.serialization.json.buildJsonObject {
                put(NodeState.RAW_INPUT, JsonPrimitive("hello"))
                put(NodeState.STARTED_AT, JsonPrimitive("2024-01-01T00:00:00Z"))
            }
            val states = kotlinx.serialization.json.buildJsonObject {
                put("", innerState)
            }
            put("s", states)
        }

        val decoded = LemlineJson.decodeFromElement<WorkflowState>(legacyElement)
        assertEquals(WorkflowName("orders"), decoded.workflowName)
        assertEquals(WorkflowVersion("v1"), decoded.workflowVersion)
        assertEquals(NodePosition.root, decoded.currentPosition)
        assertNotNull(decoded.currentStates[NodePosition.root])
        assertEquals(JsonPrimitive("hello"), decoded.currentStates[NodePosition.root]?.rawInput)
        // startedAt presence shows inner state was read; value equality checked indirectly by not-null
        assertNotNull(decoded.currentStates[NodePosition.root]?.startedAt)
    }
}
