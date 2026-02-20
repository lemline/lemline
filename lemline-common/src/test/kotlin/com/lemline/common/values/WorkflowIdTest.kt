// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowIdTest {

    @Test
    fun `WorkflowId toString`() {
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
    fun `WorkflowId wraps and exposes underlying IDV7`() {
        val workflowId = WorkflowId.random()
        val rebuilt = WorkflowId(workflowId.value)
        assertEquals(workflowId, rebuilt)
        assertEquals(workflowId.toString(), rebuilt.toString())
    }
}
