// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.IDV7
import com.lemline.common.values.info
import io.serverlessworkflow.api.WorkflowFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class WorkflowCacheTest {

    @Test
    fun `uncached schedule consumption keeps the loose evaluation mode`() {
        val definition = """
            document:
              dsl: '1.0.0'
              namespace: cache-${IDV7.random()}
              name: trigger
              version: '1.0.0'
            evaluate:
              mode: loose
            schedule:
              on:
                any:
                  - with: { type: a }
                until: length == 2
            do:
              - result:
                  set:
                    ok: true
        """.trimIndent()
        // A concurrent publication won the cache: the refreshed instance is not the cached one.
        WorkflowCache.parseYamlAndPut(definition)
        val refreshed = WorkflowDefinitionReader.read(definition, WorkflowFormat.YAML)
        assertNotSame(WorkflowCache.snapshot().entries[refreshed.info]?.workflow, refreshed)

        assertEquals("length == 2", WorkflowCache.getEventConsumption(refreshed)?.untilExpression)
    }
}
