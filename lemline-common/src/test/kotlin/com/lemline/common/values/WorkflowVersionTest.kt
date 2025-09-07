// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import com.lemline.common.random.random
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowVersionTest {

    @Test
    fun `WorkflowVersion equality, hashCode and toString`() {
        val str1 = String.random()
        val str2 = String.random()

        val v1 = WorkflowVersion(str1)
        val v2 = WorkflowVersion(str1)
        val v3 = WorkflowVersion(str2)

        // equals should return true if the wrapped values are equal
        assertEquals(v1, v2)

        // toString should return the wrapped value
        assertEquals(str1, v1.toString())
        assertEquals(str1, v2.toString())
        assertEquals(str2, v3.toString())
    }

    @Test
    fun `WorkflowVersion JSON roundtrip with LemlineJson`() {
        val str = String.random()

        val version = WorkflowVersion(str)

        val versionJson = LemlineJson.encodeToString(version)

        // Encoded as JSON strings
        assertEquals("\"$str\"", versionJson)

        // Decoding
        val decodedVersion = LemlineJson.decodeFromString<WorkflowVersion>(versionJson)

        assertEquals(version, decodedVersion)
    }
}
