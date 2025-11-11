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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for Workflow execution using CompleteOrchestrator.
 *
 * Tests sub-workflow execution to verify:
 * - Basic sub-workflow invocation
 * - Input/output handling between parent and child workflows
 * - Recursive workflow calls
 * - Multiple sub-workflow executions
 * - Integration with workflow context
 */
@ExperimentalTime
class RunWorkflowExecutionTest {

    @AfterEach
    fun cleanup() {
        // Clear the definition cache after each test to avoid interference
        DefinitionCache.clear()
    }

    @Test
    fun `workflow can execute simple sub-workflow`() = runTest {
        // Define a simple child workflow that doubles the input
        val childWorkflowYaml = $$"""
            do:
              - double:
                  set:
                    result: ${ .value * 2 }
        """

        // Register the child workflow in the cache
        getWorkflowNode(childWorkflowYaml, namespace = "test", name = "doubler", version = "0.1.0")

        // Parent workflow that calls the child
        val parentWorkflowYaml = """
            do:
              - callDoubler:
                  run:
                    workflow:
                      namespace: test
                      name: doubler
                      version: '0.1.0'
                      input:
                        value: 5
        """

        val rootNode = getWorkflowNode(parentWorkflowYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(10, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can pass input to sub-workflow`() = runTest {
        // Child workflow that greets by name
        val childWorkflowYaml = $$"""
            do:
              - greet:
                  set:
                    greeting: ${ "Hello, " + .name + "!" }
        """

        getWorkflowNode(childWorkflowYaml, namespace = "test", name = "greeter", version = "0.1.0")

        // Parent workflow
        val parentWorkflowYaml = $$"""
            do:
              - setName:
                  set:
                    userName: Alice
              - callGreeter:
                  run:
                    workflow:
                      namespace: test
                      name: greeter
                      version: '0.1.0'
                      input:
                        name: ${ .userName }
        """

        val rootNode = getWorkflowNode(parentWorkflowYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("Hello, Alice!", output["greeting"]?.jsonPrimitive?.content)
    }

    @Test
    fun `workflow can execute recursive factorial calculation`() = runTest {
        // Recursive factorial workflow
        val factorialYaml = $$"""
            do:
              - checkBaseCase:
                  switch:
                    - base:
                        when: ${ .n == 1 }
                        then: returnOne
                    - default:
                        then: computeRecursive

              - returnOne:
                  set:
                    n: 1
                  then: end

              - computeRecursive:
                  do:
                    - callFactorialNMinus1:
                        run:
                          workflow:
                            namespace: test
                            name: factorial
                            version: '0.1.0'
                            input:
                              n: ${ .n - 1 }
                    - multiplyResults:
                        set:
                          n: ${ .n * $workflow.input.n }
                        then: end
        """

        getWorkflowNode(factorialYaml, namespace = "test", name = "factorial", version = "0.1.0")

        // Call factorial(5)
        val rootNode = getWorkflowNode(factorialYaml, namespace = "test", name = "factorial-main", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("n" to JsonPrimitive(5)))) as JsonObject

        // 5! = 120
        assertEquals(120, output["n"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can call multiple sub-workflows in sequence`() = runTest {
        // First child workflow
        val adderYaml = $$"""
            do:
              - add:
                  set:
                    result: ${ .a + .b }
        """
        getWorkflowNode(adderYaml, namespace = "test", name = "adder", version = "0.1.0")

        // Second child workflow
        val multiplierYaml = $$"""
            do:
              - multiply:
                  set:
                    result: ${ .value * 2 }
        """
        getWorkflowNode(multiplierYaml, namespace = "test", name = "multiplier", version = "0.1.0")

        // Parent workflow calling both
        val parentYaml = $$"""
            do:
              - callAdder:
                  run:
                    workflow:
                      namespace: test
                      name: adder
                      version: '0.1.0'
                      input:
                        a: 3
                        b: 7
              - callMultiplier:
                  run:
                    workflow:
                      namespace: test
                      name: multiplier
                      version: '0.1.0'
                      input:
                        value: ${ .result }
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // (3 + 7) * 2 = 20
        assertEquals(20, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can use sub-workflow output in subsequent tasks`() = runTest {
        // Child workflow that processes data
        val processorYaml = $$"""
            do:
              - process:
                  set:
                    processed: true
                    value: ${ .input * 10 }
        """
        getWorkflowNode(processorYaml, namespace = "test", name = "processor", version = "0.1.0")

        // Parent workflow
        val parentYaml = $$"""
            do:
              - callProcessor:
                  run:
                    workflow:
                      namespace: test
                      name: processor
                      version: '0.1.0'
                      input:
                        input: 5
              - useResult:
                  set:
                    finalValue: ${ .value + 10 }
                    wasProcessed: ${ .processed }
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(60, output["finalValue"]?.jsonPrimitive?.int)
        assertEquals(true, output["wasProcessed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `workflow output can be transformed with output as`() = runTest {
        // Child workflow
        val childYaml = """
            do:
              - compute:
                  set:
                    value: 42
                    status: success
        """
        getWorkflowNode(childYaml, namespace = "test", name = "child", version = "0.1.0")

        // Parent workflow with output transformation
        val parentYaml = $$"""
            do:
              - callChild:
                  run:
                    workflow:
                      namespace: test
                      name: child
                      version: '0.1.0'
                  output:
                    as: '${ {result: .value} }'
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(42, output["result"]?.jsonPrimitive?.int)
        assertTrue(!output.containsKey("status"))
    }

    @Test
    fun `workflow can execute sub-workflow with complex input`() = runTest {
        // Child workflow that processes complex data
        val processorYaml = $$"""
            do:
              - process:
                  set:
                    fullName: ${ .firstName + " " + .lastName }
                    age: ${ .age }
        """
        getWorkflowNode(processorYaml, namespace = "test", name = "person-processor", version = "0.1.0")

        // Parent workflow
        val parentYaml = """
            do:
              - callProcessor:
                  run:
                    workflow:
                      namespace: test
                      name: person-processor
                      version: '0.1.0'
                      input:
                        firstName: John
                        lastName: Doe
                        age: 30
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("John Doe", output["fullName"]?.jsonPrimitive?.content)
        assertEquals(30, output["age"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can chain multiple nested sub-workflows`() = runTest {
        // Level 3 workflow
        val level3Yaml = $$"""
            do:
              - compute:
                  set:
                    result: ${ .value + 1 }
        """
        getWorkflowNode(level3Yaml, namespace = "test", name = "level3", version = "0.1.0")

        // Level 2 workflow calls level 3
        val level2Yaml = $$"""
            do:
              - callLevel3:
                  run:
                    workflow:
                      namespace: test
                      name: level3
                      version: '0.1.0'
                      input:
                        value: ${ .value * 2 }
        """
        getWorkflowNode(level2Yaml, namespace = "test", name = "level2", version = "0.1.0")

        // Level 1 workflow calls level 2
        val level1Yaml = $$"""
            do:
              - callLevel2:
                  run:
                    workflow:
                      namespace: test
                      name: level2
                      version: '0.1.0'
                      input:
                        value: ${ .value + 3 }
        """
        getWorkflowNode(level1Yaml, namespace = "test", name = "level1", version = "0.1.0")

        // Root workflow calls level 1
        val rootYaml = """
            do:
              - callLevel1:
                  run:
                    workflow:
                      namespace: test
                      name: level1
                      version: '0.1.0'
                      input:
                        value: 5
        """

        val rootNode = getWorkflowNode(rootYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // ((5 + 3) * 2) + 1 = 17
        assertEquals(17, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can execute sub-workflow with default input`() = runTest {
        // Child workflow that uses input or defaults
        val childYaml = """
            do:
              - setDefaults:
                  set:
                    value: 10
        """
        getWorkflowNode(childYaml, namespace = "test", name = "defaulter", version = "0.1.0")

        // Parent workflow without explicit input
        val parentYaml = """
            do:
              - callChild:
                  run:
                    workflow:
                      namespace: test
                      name: defaulter
                      version: '0.1.0'
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(10, output["value"]?.jsonPrimitive?.int)
    }

    @Test
    fun `workflow can execute sub-workflow asynchronously without waiting`() = runTest {
        // Child workflow
        val childYaml = """
            do:
              - slowTask:
                  set:
                    completed: true
        """
        getWorkflowNode(childYaml, namespace = "test", name = "slow-workflow", version = "0.1.0")

        // Parent workflow with await: false
        val parentYaml = """
            do:
              - callAsync:
                  run:
                    await: false
                    workflow:
                      namespace: test
                      name: slow-workflow
                      version: '0.1.0'
              - continueImmediately:
                  set:
                    continued: true
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Parent should have continued immediately, not waiting for child
        assertEquals(true, output["continued"]?.jsonPrimitive?.content?.toBoolean())
        // Child output should NOT be in parent output (fire-and-forget)
        assertEquals(false, output.containsKey("completed"))
    }

    @Test
    fun `workflow can use workflow context in sub-workflow calls`() = runTest {
        // Child workflow
        val childYaml = $$"""
            do:
              - compute:
                  set:
                    doubled: ${ .original * 2 }
                    parentValue: ${ .original }
        """
        getWorkflowNode(childYaml, namespace = "test", name = "context-user", version = "0.1.0")

        // Parent workflow that passes context
        val parentYaml = $$"""
            do:
              - setContext:
                  set:
                    myValue: 15
              - callChild:
                  run:
                    workflow:
                      namespace: test
                      name: context-user
                      version: '0.1.0'
                      input:
                        original: ${ .myValue }
        """

        val rootNode = getWorkflowNode(parentYaml, namespace = "test", name = "parent", version = "0.1.0")
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(30, output["doubled"]?.jsonPrimitive?.int)
        assertEquals(15, output["parentValue"]?.jsonPrimitive?.int)
    }
}
