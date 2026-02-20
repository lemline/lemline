// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowInfoTest {

    @Test
    fun `qualified name uses namespace name and version`() {
        val workflowInfo = WorkflowInfo(
            namespace = WorkflowNamespace("space"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("v1"),
        )
        assertEquals("space/orders:v1", workflowInfo.qualifiedName)
    }

    @Test
    fun `data class equality remains deterministic`() {
        val first = WorkflowInfo(
            namespace = WorkflowNamespace("space"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("v1"),
        )
        val second = WorkflowInfo(
            namespace = WorkflowNamespace("space"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("v1"),
        )
        assertEquals(first, second)
    }
}
