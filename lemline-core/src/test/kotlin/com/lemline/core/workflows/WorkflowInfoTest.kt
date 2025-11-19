// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalTime::class)
class WorkflowInfoTest {

    @Test
    fun `WorkflowInfo serializes and deserializes`() {
        val workflowInfo = WorkflowInfo.random()

        val encoded = workflowInfo.toJsonString()
        val decoded = WorkflowInfo.fromJsonString(encoded)

        assertEquals(workflowInfo, decoded)
    }

    @Test
    fun `serialization uses compact field names for backward compatibility`() {
        val workflowInfo = WorkflowInfo.random()
        val json = LemlineJson.encodeToElement(workflowInfo)
        // Top-level keys must be short names: i, n, v, p, s
        require(json is JsonObject)
        val keys = json.keys
        assertTrue("s" in keys)
        assertTrue("n" in keys)
        assertTrue("v" in keys)
        // Ensure no long names accidentally appear
        assertTrue("workflowNamespace" !in keys)
        assertTrue("workflowName" !in keys)
        assertTrue("workflowVersion" !in keys)
    }

    @Test
    fun `can deserialize legacy compact json (backward compatibility)`() {
        // Build a legacy-compatible JSON string using compact field names and compact NodeState keys.
        val legacyElement = buildJsonObject {
            put("s", JsonPrimitive("space"))
            put("n", JsonPrimitive("orders"))
            put("v", JsonPrimitive("v1"))
        }

        val decoded = LemlineJson.decodeFromElement<WorkflowInfo>(legacyElement)
        assertEquals(WorkflowNamespace("space"), decoded.workflowNamespace)
        assertEquals(WorkflowName("orders"), decoded.workflowName)
        assertEquals(WorkflowVersion("v1"), decoded.workflowVersion)
    }
}
