// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for DoTask execution with complete workflows using ExecutionOrchestrator.
 *
 * Tests the sequential execution of tasks within a do block,
 * verifying proper data flow and scope management.
 */
@ExperimentalTime
class DoTaskExecutionTest {

    @Test
    fun `do task executes tasks sequentially`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  set:
                    value: 10
              - step2:
                  set:
                    doubled: ${ .value * 2 }
              - step3:
                  set:
                    result: ${ .doubled + 5 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(10, output["value"]?.jsonPrimitive?.int)
        assertEquals(20, output["doubled"]?.jsonPrimitive?.int)
        assertEquals(25, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `do task passes data between tasks`() = runTest {
        val yaml = $$"""
            do:
              - initialize:
                  set:
                    counter: 0
                    items: []
              - addItem1:
                  set:
                    items: ${ .items + ["first"] }
                    counter: ${ .counter + 1 }
              - addItem2:
                  set:
                    items: ${ .items + ["second"] }
                    counter: ${ .counter + 1 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(2, output["counter"]?.jsonPrimitive?.int)
        val items = output["items"] as JsonArray
        assertEquals(2, items.size)
        assertEquals("first", items[0].jsonPrimitive.content)
        assertEquals("second", items[1].jsonPrimitive.content)
    }

    @Test
    fun `do task can access task metadata in expressions`() = runTest {
        val yaml = $$"""
            do:
              - taskWithMetadata:
                  set:
                    taskName: ${ @task.name }
                    taskRef: ${ @task.reference }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(42)) as JsonObject

        assertEquals("taskWithMetadata", output["taskName"]?.jsonPrimitive?.content)
        assertEquals("/do/0/taskWithMetadata", output["taskRef"]?.jsonPrimitive?.content)
    }

    @Test
    fun `do task can transform input in first task`() = runTest {
        val yaml = $$"""
            do:
              - processInput:
                  input:
                    from: ${ . * 10 }
                  set:
                    result: ${ @input }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(5)) as JsonObject

        assertEquals(50, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `do task can merge multiple objects`() = runTest {
        val yaml = $$"""
            do:
              - createUser:
                  set:
                    name: ${ "Alice" }
                    age: ${ 30 }
              - addMetadata:
                  set:
                    timestamp: ${ "2025-01-01" }
                    version: ${ 1 }
              - combine:
                  set:
                    user: '${ {name: .name, age: .age} }'
                    metadata: '${ {timestamp: .timestamp, version: .version} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val user = output["user"]?.jsonObject
        assertNotNull(user)
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30, user["age"]?.jsonPrimitive?.int)

        val metadata = output["metadata"]?.jsonObject
        assertNotNull(metadata)
        assertEquals("2025-01-01", metadata["timestamp"]?.jsonPrimitive?.content)
        assertEquals(1, metadata["version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `do task can use conditional logic in set`() = runTest {
        val yaml = $$"""
            do:
              - checkScore:
                  set:
                    score: 85
              - assignGrade:
                  set:
                    grade: ${ if .score >= 90 then "A" elif .score >= 80 then "B" else "C" end }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("B", output["grade"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nested do tasks execute correctly`() = runTest {
        val yaml = $$"""
            do:
              - outer:
                  do:
                    - inner1:
                        set:
                          value: 10
                    - inner2:
                        set:
                          doubled: ${ .value * 2 }
              - final:
                  set:
                    result: ${ .doubled + 5 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(25, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    @org.junit.jupiter.api.Disabled("\$workflow scope variable not yet implemented in ExecutionOrchestrator")
    fun `do task can access workflow descriptor`() = runTest {
        val yaml = $$"""
            do:
              - checkWorkflow:
                  set:
                    hasWorkflowId: ${ @workflow.id != null }
                    hasWorkflowInput: ${ @workflow.input != null }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(42)) as JsonObject

        assertEquals(true, output["hasWorkflowId"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, output["hasWorkflowInput"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `do task preserves data through multiple transformations`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  set:
                    base: 100
              - step2:
                  set:
                    multiplied: ${ .base * 2 }
              - step3:
                  set:
                    added: ${ .multiplied + 50 }
              - step4:
                  set:
                    final: ${ .added / 2 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(100, output["base"]?.jsonPrimitive?.int)
        assertEquals(200, output["multiplied"]?.jsonPrimitive?.int)
        assertEquals(250, output["added"]?.jsonPrimitive?.int)
        assertEquals(125, output["final"]?.jsonPrimitive?.int)
    }

    @Test
    fun `do task with output transformation`() = runTest {
        val yaml = $$"""
            do:
              - process:
                  set:
                    value: ${ 42 }
                    name: ${ "test" }
                  output:
                    as: '${ {result: .value, label: .name} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(42, output["result"]?.jsonPrimitive?.int)
        assertEquals("test", output["label"]?.jsonPrimitive?.content)
    }
}
