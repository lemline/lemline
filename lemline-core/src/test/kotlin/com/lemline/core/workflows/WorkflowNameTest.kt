// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowNameTest {

    @Test
    fun `WorkflowName equality, hashCode and toString`() {
        val a1 = WorkflowName("orders")
        val a2 = WorkflowName("orders")
        val b = WorkflowName("payments")

        // value class should implement structural equality
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        assertTrue(a1 != b)

        // toString should return the wrapped value
        assertEquals("orders", a1.toString())
        assertEquals("payments", b.toString())
    }

    @Test
    fun `WorkflowName JSON roundtrip with LemlineJson`() {
        val name = WorkflowName("analytics")

        val nameJson = LemlineJson.encodeToString(name)

        // Encoded as JSON strings
        assertEquals("\"analytics\"", nameJson)

        // Roundtrip
        val decodedName = LemlineJson.decodeFromString<WorkflowName>(nameJson)

        assertEquals(name, decodedName)
    }

    @Test
    fun `WorkflowName can be used as map keys`() {
        val m = linkedMapOf<WorkflowName, Int>()
        val name = WorkflowName("orders")
        m[name] = 1

        assertTrue(m.containsKey(WorkflowName("orders")))
        assertEquals(1, m[WorkflowName("orders")])
    }
}
