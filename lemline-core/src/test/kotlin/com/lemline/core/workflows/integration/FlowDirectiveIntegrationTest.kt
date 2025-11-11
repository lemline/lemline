// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows.integration

import com.lemline.core.execution.ExecutionOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Migrated version of FlowDirectiveTest using the new ExecutionOrchestrator implementation.
 * Tests flow control directives: continue, exit, end, and named goto.
 */
@ExperimentalTime
class FlowDirectiveIntegrationTest {

    @Test
    fun `test continue`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
                  then: continue
              - second:
                  set:
                    value: ${ .value + "2" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("123"), output)
    }

    @Test
    fun `test nested continue`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: ${ .value + "2a" }
                        then: continue
                    - secondB:
                        set:
                          value: ${ .value + "2b" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12a2b3"), output)
    }

    @Test
    fun `test end`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  set:
                    value: ${ .value + "2" }
                  then: end
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12"), output)
    }

    @Test
    fun `test end with output as`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  set:
                    value: ${ .value + "2" }
                  output:
                    as: ${ .value }
                  then: end
              - third:
                  set:
                    value: ${ .value + "3" }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12"), output)
    }

    @Test
    fun `test nested end`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: ${ .value + "2a" }
                        then: end
                    - secondB:
                        set:
                          value: ${ .value + "2b" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        // BUG: END directive not properly propagating from nested do block
        // Expected: "12a" (END should stop workflow from secondA)
        // Actual: "12a3" (incorrectly continues to third task)
        // The END directive should recursively propagate to parent and terminate workflow
        // See NodeProcessor.continueToEnd() - it returns END flowDirective to parent
        assertEquals(JsonPrimitive("12a"), output)
    }

    @Test
    fun `test exit`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  set:
                    value: ${ .value + "2" }
                  then: exit
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12"), output)
    }

    @Test
    fun `test exit with output as`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  set:
                    value: ${ .value + "2" }
                  output:
                    as: ${ .value }
                  then: exit
              - third:
                  set:
                    value: ${ .value + "3" }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12"), output)
    }

    @Test
    fun `test nested exit`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: ${ .value + "2a" }
                        then: exit
                    - secondB:
                        set:
                          value: ${ .value + "2b" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12a3"), output)
    }

    @Test
    fun `test named then`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
                  then: third
              - second:
                  set:
                    value: ${ .value + "2" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("13"), output)
    }

    @Test
    fun `test nested named then`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: ${ .value + "2a" }
                        then: secondC
                    - secondB:
                        set:
                          value: ${ .value + "2b" }
                    - secondC:
                        set:
                          value: ${ .value + "2c" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        assertEquals(JsonPrimitive("12a2c3"), output)
    }
}
