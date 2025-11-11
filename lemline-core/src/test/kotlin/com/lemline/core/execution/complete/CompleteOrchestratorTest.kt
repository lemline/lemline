// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for CompleteOrchestrator.
 *
 * CompleteOrchestrator executes workflows from start to finish:
 * - Activities execute fully (real HTTP calls, shell commands, scripts)
 * - Delays actually wait using coroutines
 * - Sub-workflows execute inline recursively
 * - Returns final workflow output
 *
 * This test suite focuses on end-to-end execution without pausing.
 */
@ExperimentalTime
class CompleteOrchestratorTest {

    @AfterEach
    fun cleanup() {
        DefinitionCache.clear()
    }

    // ========================================
    // Basic Execution Tests
    // ========================================

    @Test
    fun `should execute simple workflow with set tasks and then directive`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  set:
                    value: 1
                  then: step3
              - step2:
                  set:
                    value: 2
              - step3:
                  set:
                    result: ${ .value * 10 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(10, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should execute workflow with data transformations`() = runTest {
        val yaml = $$"""
            do:
              - createData:
                  set:
                    numbers: [1, 2, 3, 4, 5]
                    sum: 0
              - sumLoop:
                  for:
                    in: ${ .numbers }
                  do:
                    - addNumber:
                        set:
                          sum: ${ .sum + @item }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(15, output["sum"]?.jsonPrimitive?.int)
    }

    // ========================================
    // Activity Execution Tests
    // ========================================

    @Test
    fun `should execute HTTP call activity completely`() = runTest {
        val yaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should complete the HTTP call and return the result
        assertEquals(1, output["id"]?.jsonPrimitive?.int)
        assertTrue(output.containsKey("title"))
        assertTrue(output.containsKey("body"))
    }

    @Test
    fun `should execute shell command activity completely`() = runTest {
        val yaml = """
            do:
              - echoCommand:
                  run:
                    shell:
                      command: echo "Hello World"
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should complete the shell command and return stdout as JsonPrimitive
        assertEquals("Hello World", (output as JsonPrimitive).content)
    }

    @Test
    fun `should execute multiple activities in sequence`() = runTest {
        val yaml = """
            do:
              - firstCall:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
              - secondCall:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/2
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should complete both HTTP calls
        assertEquals(2, output["id"]?.jsonPrimitive?.int)
    }

    // ========================================
    // Delay/Wait Tests
    // ========================================

    @Test
    fun `should handle wait task and continue execution`() = runTest {
        val yaml = """
            do:
              - setValue:
                  set:
                    before: true
              - waitStep:
                  wait:
                    milliseconds: 1
              - setAfter:
                  set:
                    after: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should execute tasks before and after wait
        assertEquals(true, output["before"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, output["after"]?.jsonPrimitive?.content?.toBoolean())
    }

    // ========================================
    // Sub-workflow Tests
    // ========================================

    @Test
    fun `should execute sub-workflow inline and wait for completion`() = runTest {
        // Define child workflow
        val childYaml = $$"""
            do:
              - double:
                  set:
                    result: ${ .value * 2 }
        """
        getWorkflowNode(childYaml, namespace = "test", name = "doubler", version = "0.1.0")

        // Parent workflow
        val parentYaml = """
            do:
              - callChild:
                  run:
                    workflow:
                      namespace: test
                      name: doubler
                      version: '0.1.0'
                      input:
                        value: 5
        """
        val rootNode = getWorkflowNode(parentYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should complete child workflow and return result to parent
        assertEquals(10, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should execute fire-and-forget sub-workflow asynchronously`() = runTest {
        // Define child workflow
        val childYaml = """
            do:
              - process:
                  set:
                    processed: true
        """
        getWorkflowNode(childYaml, namespace = "test", name = "processor", version = "0.1.0")

        // Parent workflow with await=false
        val parentYaml = $$"""
            do:
              - prepareData:
                  set:
                    data: "test"
              - launchChild:
                  run:
                    await: false
                    workflow:
                      namespace: test
                      name: processor
                      version: '0.1.0'
                      input:
                        value: ${ .data }
              - continueParent:
                  set:
                    parentDone: true
        """
        val rootNode = getWorkflowNode(parentYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Parent should complete immediately without waiting for child
        assertEquals(true, output["parentDone"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("test", output["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should execute nested sub-workflows recursively`() = runTest {
        // Grandchild workflow
        val grandchildYaml = """
            do:
              - setValue:
                  set:
                    level: 3
        """
        getWorkflowNode(grandchildYaml, namespace = "test", name = "grandchild", version = "0.1.0")

        // Child workflow that calls grandchild
        val childYaml = """
            do:
              - callGrandchild:
                  run:
                    workflow:
                      namespace: test
                      name: grandchild
                      version: '0.1.0'
              - setLevel:
                  set:
                    level: 2
        """
        getWorkflowNode(childYaml, namespace = "test", name = "child", version = "0.1.0")

        // Parent workflow
        val parentYaml = """
            do:
              - callChild:
                  run:
                    workflow:
                      namespace: test
                      name: child
                      version: '0.1.0'
              - setLevel:
                  set:
                    level: 1
        """
        val rootNode = getWorkflowNode(parentYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should complete all nested workflows
        assertEquals(1, output["level"]?.jsonPrimitive?.int)
    }

    // ========================================
    // Control Flow Tests
    // ========================================

    @Test
    fun `should handle conditional branches`() = runTest {
        val yaml = $$"""
            do:
              - setValue:
                  set:
                    condition: true
              - thenBranch:
                  if: ${ .condition == true }
                  set:
                    result: "condition was true"
              - elseBranch:
                  if: ${ .condition == false }
                  set:
                    result: "condition was false"
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("condition was true", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should handle loops completely`() = runTest {
        val yaml = $$"""
            do:
              - initialize:
                  set:
                    numbers: [1, 2, 3]
                    sum: 0
              - sumLoop:
                  for:
                    in: ${ .numbers }
                  do:
                    - add:
                        set:
                          sum: ${ .sum + @item }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(6, output["sum"]?.jsonPrimitive?.int)
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    fun `should handle try-catch blocks`() = runTest {
        val yaml = """
            do:
              - tryBlock:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            errorHandled: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["errorHandled"]?.jsonPrimitive?.boolean)
    }

    // ========================================
    // State Management Tests
    // ========================================

    @Test
    fun `should maintain workflow state correctly`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  set:
                    a: 1
              - step2:
                  set:
                    b: 2
              - step3:
                  set:
                    c: ${ .a + .b }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should accumulate state through execution and produce correct output
        assertEquals(3, output["c"]?.jsonPrimitive?.int)
        assertEquals(1, output["a"]?.jsonPrimitive?.int)
        assertEquals(2, output["b"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should handle exported context correctly`() = runTest {
        val yaml = $$"""
            do:
              - exportData:
                  set:
                    myData: 1
                  export:
                    as: 
                      myData: 2
              - useExported:
                  set:
                    result: ${ .myData + $context.myData }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(JsonPrimitive(3), output["result"])
    }

    // ========================================
    // Integration Tests
    // ========================================

    @Test
    fun `should execute complex workflow end-to-end`() = runTest {
        val yaml = $$"""
            do:
              - initialize:
                  set:
                    values: [10, 20, 30]
                    sum: 0
              - sumLoop:
                  for:
                    in: ${ .values }
                  do:
                    - add:
                        set:
                          sum: ${ .sum + @item }
                  output:
                    as: ${ . }
              - checkSuccess:
                  if: ${ .sum == 60 }
                  set:
                    status: "success"
              - checkFailure:
                  if: ${ .sum != 60 }
                  set:
                    status: "failure"
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("success", output["status"]?.jsonPrimitive?.content)
        assertEquals(60, output["sum"]?.jsonPrimitive?.int)
    }
}
