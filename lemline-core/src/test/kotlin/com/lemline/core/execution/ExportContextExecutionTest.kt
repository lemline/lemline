// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Tests for export.as directive in the new execution model.
 *
 * The export.as directive allows tasks to export data to the workflow context ($context),
 * making it available to subsequent tasks.
 */
class ExportContextExecutionTest {

    @Test
    fun `export context using expression syntax`() = runTest {
        val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as: "${ {ctx: .} }"
              - second:
                  set:
                    number: ${ @context.ctx.foo }
        """
        val rootNode = getWorkflowNode(yaml)

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf()))

        // Verify output
        output as JsonObject
        assertEquals(42, output["number"]?.jsonPrimitive?.int)
    }

    @Test
    fun `export context using JSON object`() = runTest {
        val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as:
                      ctx: .
              - second:
                  set:
                    number: ${ @context.ctx.foo }
        """
        val rootNode = getWorkflowNode(yaml)

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf())) as JsonObject

        // Verify output
        assertEquals(42, output["number"]?.jsonPrimitive?.int)
    }

    @Test
    fun `export partial data to context`() = runTest {
        val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                    bar: "hello"
                  export:
                    as:
                      onlyFoo: .foo
              - second:
                  set:
                    fromContext: ${ @context.onlyFoo }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf())) as JsonObject

        // Verify output - should only have foo in context
        assertEquals(42, output["fromContext"]?.jsonPrimitive?.int)
    }

    @Test
    fun `multiple tasks can export to context`() = runTest {
        val yaml = $$"""
            do:
              - first:
                  set:
                    value1: 10
                  export:
                    as:
                      first: .value1
              - second:
                  set:
                    value2: 20
                  export:
                    as:
                      first: ${ @context.first }
                      second: .value2
              - third:
                  set:
                    sum: ${ @context.first + @context.second }
        """
        val rootNode = getWorkflowNode(yaml)

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf())) as JsonObject

        // Verify output - both values should be preserved in context
        assertEquals(30, output["sum"]?.jsonPrimitive?.int)
    }

    @Test
    fun `export can overwrite previous context values`() = runTest {
        val yaml = $$"""
            do:
              - first:
                  set:
                    value: 10
                  export:
                    as:
                      shared: .value
              - second:
                  set:
                    value: 20
                  export:
                    as:
                      shared: .value
              - third:
                  set:
                    result: ${ @context.shared }
        """
        val rootNode = getWorkflowNode(yaml)
        val states = mutableMapOf<com.lemline.core.nodes.Node<*>, com.lemline.core.states.NodeState>()

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf()), states) as JsonObject

        // Should have the second value (20), not the first
        assertEquals(20, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `export works with nested tasks`() = runTest {
        val yaml = $$"""
            do:
              - outer:
                  do:
                    - inner:
                        set:
                          nested: "value"
                        export:
                          as:
                            fromNested: .nested
              - useNested:
                  set:
                    result: ${ @context.fromNested }
        """
        val rootNode = getWorkflowNode(yaml)
        val states = mutableMapOf<com.lemline.core.nodes.Node<*>, com.lemline.core.states.NodeState>()

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf()), states) as JsonObject

        // Verify nested task could export to context
        assertEquals("value", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `export can transform data before exporting`() = runTest {
        val yaml = $$"""
            do:
              - compute:
                  set:
                    x: 10
                    y: 20
                  export:
                    as:
                      sum: ${ .x + .y }
              - use:
                  set:
                    doubled: ${ @context.sum * 2 }
        """
        val rootNode = getWorkflowNode(yaml)
        val states = mutableMapOf<com.lemline.core.nodes.Node<*>, com.lemline.core.states.NodeState>()

        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf()), states) as JsonObject

        // Verify transformation was applied before export
        assertEquals(60, output["doubled"]?.jsonPrimitive?.int)
    }
}
