// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import com.lemline.common.random.random
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowNameTest {

    @Test
    fun `WorkflowName toString`() {
        val n1 = WorkflowName("orders")
        val n2 = WorkflowName("orders")
        val n3 = WorkflowName("payments")

        // equals should return true if the wrapped values are equal
        assertEquals(n1, n2)

        // toString should return the wrapped value
        assertEquals("orders", n1.toString())
        assertEquals("orders", n2.toString())
        assertEquals("payments", n3.toString())
    }

    @Test
    fun `WorkflowName JSON roundtrip with LemlineJson`() {
        val str = String.random()
        val name = WorkflowName(str)

        val nameJson = LemlineJson.encodeToString(name)

        // Encoded as JSON strings
        assertEquals("\"$str\"", nameJson)

        // Decoding
        val decodedName = LemlineJson.decodeFromString<WorkflowName>(nameJson)

        assertEquals(name, decodedName)
    }
}
