// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows.integration

import com.lemline.core.execution.complete.CompleteOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Migrated version of DataFlowTest using the new CompleteOrchestrator implementation.
 * Tests input/output transformations at workflow and task levels.
 */
@ExperimentalTime
class DataFlowIntegrationTest {

    @Test
    fun `check workflow input-from (expr)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            input:
              from: "${ {in: .} }"
            do:
              - first:
                  set:
                    value: ${ .in }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow input-from (json)`() = runTest {
        val doYaml = $$"""
            input:
              from: "{in: .}"
            do:
              - first:
                  set:
                    value: ${ .in }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive("foo")) as JsonObject

        assertEquals("foo", output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow input-from (yaml)`() = runTest {
        val doYaml = $$"""
            input:
              from:
                in: .
            do:
              - first:
                  set:
                    value: ${ .in }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive("foo")) as JsonObject

        assertEquals("foo", output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow output-as (expr)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ . }
            output:
              as: "${ {out: .value} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow output-as (json)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ . }
            output:
              as: "{out: .value}"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow output-as (yaml)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ . }
            output:
              as:
                out: .value
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check workflow input-from and output-as`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            input:
              from: "${ {in: .} }"
            do:
              - first:
                  set:
                    value: ${ .in }
            output:
              as: "${ {out: .value} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task input-from (expr)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  input:
                    from: "${ {in: .} }"
                  set:
                    value: "${ .in }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task input-from (json)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  input:
                    from: "{in: .}"
                  set:
                    value: "${ .in }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task input-from (yaml)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  input:
                    from:
                        in: .
                  set:
                    value: "${ .in }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task output-as (expr)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: "${ . }"
                  output:
                    as: "${ {out: .value} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task output-as (json)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: "${ . }"
                  output:
                    as: "{out: .value}"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task output-as (yaml)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: "${ . }"
                  output:
                    as:
                      out: .value
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check task input-from & output-as`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  input:
                    from: "${ {in: .} }"
                  set:
                    value: "${ .in }"
                  output:
                    as: "${ {out: .value} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check multiple tasks input-from & output-as`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            do:
              - first:
                  input:
                    from: "${ {in1: .} }"
                  set:
                    value: "${ .in1 }"
                  output:
                    as: "${ {out1: .value} }"
              - second:
                  input:
                    from: "${ {in2: .out1} }"
                  set:
                    value: "${ .in2 }"
                  output:
                    as: "${ {out2: .value} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out2"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check all together`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            input:
              from: "${ {in: .} }"
            do:
              - first:
                  input:
                    from: "${ {in1: .in} }"
                  set:
                    value: "${ .in1 }"
                  output:
                    as: "${ {out1: .value} }"
              - second:
                  input:
                    from: "${ {in2: .out1} }"
                  set:
                    value: "${ .in2 }"
                  output:
                    as: "${ {out2: .value} }"
            output:
              as: "${ {out: .out2} }"
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check all together (json)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            input:
              from: "{in: .}"
            do:
              - first:
                  input:
                    from: {in1: .in}
                  set:
                    value: ${ .in1 }
                  output:
                    as: {out1: .value}
              - second:
                  input:
                    from: {in2: .out1}
                  set:
                    value: ${ .in2 }
                  output:
                    as: {out2: .value}
            output:
              as: {out: .out2}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `check all together (yaml)`() = runTest {
        val str = "foo"
        val doYaml = $$"""
            input:
              from: "{in: .}"
            do:
              - first:
                  input:
                    from:
                      in1: .in
                  set:
                    value: ${ .in1 }
                  output:
                    as:
                      out1: .value
              - second:
                  input:
                    from:
                      in2: .out1
                  set:
                    value: ${ .in2 }
                  output:
                    as:
                      out2: .value
            output:
              as:
                out: .out2
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(str)) as JsonObject

        assertEquals(str, output["out"]?.jsonPrimitive?.content)
    }
}
