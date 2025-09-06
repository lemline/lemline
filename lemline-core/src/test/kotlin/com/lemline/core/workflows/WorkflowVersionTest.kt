// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkflowVersionTest {

    @Test
    fun `WorkflowVersion equality, hashCode and toString`() {
        val v1a = WorkflowVersion("v1")
        val v1b = WorkflowVersion("v1")
        val v2 = WorkflowVersion("v2")

        assertEquals(v1a, v1b)
        assertEquals(v1a.hashCode(), v1b.hashCode())
        assertNotEquals(v1a, v2)

        assertEquals("v1", v1a.toString())
        assertEquals("v2", v2.toString())
    }

    @Test
    fun `WorkflowVersion JSON roundtrip with LemlineJson`() {
        val version = WorkflowVersion("2024.12")

        val versionJson = LemlineJson.encodeToString(version)

        // Encoded as JSON strings
        assertEquals("\"2024.12\"", versionJson)

        // Roundtrip
        val decodedVersion = LemlineJson.decodeFromString<WorkflowVersion>(versionJson)

        assertEquals(version, decodedVersion)
    }
}
