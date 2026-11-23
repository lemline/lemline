// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.IDV7
import com.lemline.common.values.info
import io.serverlessworkflow.api.WorkflowFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowCacheTest {

    @Test
    fun `uncached schedule consumption keeps the loose evaluation mode`() {
        val definition = """
            document:
              dsl: '1.0.3'
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
    @Test
    fun `suppressed remote function tree does not hide extension listeners of a normal call`() = runBlocking {
        val namespace = "cache-${IDV7.random()}"
        val reference = "https://example.com/extended.yaml"
        val workflow = WorkflowCache.parseYamlAndPut("""
            document:
              dsl: '1.0.3'
              namespace: $namespace
              name: extended
              version: '1.0.0'
            use:
              extensions:
                - observe:
                    extend: set
                    before:
                      - ready: { listen: { to: { one: { with: { type: example.ready } } } } }
            do:
              - invoke: { call: '$reference' }
        """.trimIndent())
        val function = WorkflowCache.parseYamlAndPut("""
            document:
              dsl: '1.0.3'
              namespace: $namespace
              name: function
              version: '1.0.0'
            do:
              - answer: { set: { value: 42 } }
        """.trimIndent()).`do`.single().task
        // This worker only reached the function from inside an extension: its tree omits extensions.
        WorkflowCache.functionNodes(workflow, function, reference, suppressed = true)

        assertTrue(WorkflowCache.requiresFunctionListenerRefresh(workflow.info.namespace))
        WorkflowCache.loadFunctionListeners(workflow.info, reference) { function }

        assertTrue(WorkflowCache.getListenTasks(workflow.info).any {
            !it.isFromFunction && it.nodePosition.toString().startsWith("/_fn/")
        })
    }
}
