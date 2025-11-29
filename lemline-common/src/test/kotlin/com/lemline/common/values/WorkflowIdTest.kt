// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowIdTest {

    @Test
    fun `WorkflowName toString`() {
        val id1 = IDV7.random()
        val id2 = IDV7.random()
        val w1 = WorkflowId(id1)
        val w2 = WorkflowId(id1)
        val w3 = WorkflowId(id2)

        // equals should return true if the wrapped values are equal
        assertEquals(w1, w2)

        // toString should return the wrapped value
        assertEquals(id1.value.toString(), w1.toString())
        assertEquals(id1.value.toString(), w2.toString())
        assertEquals(id2.value.toString(), w3.toString())
    }

    @Test
    fun `WorkflowName JSON roundtrip with LemlineJson`() {
        val workflowId = WorkflowId.random()

        val serialized: String = LemlineJson.encodeToString(workflowId)

        // Encoded as JSON strings
        assertEquals("\"$workflowId\"", serialized)

        // Decoding
        val decoded = LemlineJson.decodeFromString<WorkflowId>(serialized)

        assertEquals(workflowId, decoded)
    }
}
