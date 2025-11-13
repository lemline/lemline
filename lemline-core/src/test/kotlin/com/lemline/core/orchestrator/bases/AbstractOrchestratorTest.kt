package com.lemline.core.orchestrator.bases

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.getWorkflowNode
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Abstract base class providing shared test scenarios for workflow orchestrators.
 *
 * This class contains all common workflow execution tests that should pass for both:
 * - **CompleteOrchestrator**: Executes workflows to completion in one go
 * - **PausableOrchestrator**: Executes workflows with pause/resume at boundaries
 *
 * Subclasses must implement [executeWorkflow] to adapt the orchestrator's specific
 * execution model to return the final workflow output.
 *
 * ## Test Categories
 *
 * - **Basic Execution**: Simple workflows with set tasks and control flow
 * - **Data Transformations**: Workflows with data manipulation and JQ expressions
 * - **Activities**: HTTP calls, shell commands, script execution
 * - **Wait Tasks**: Delay handling
 * - **Sub-workflows**: Child workflow execution (await=true and await=false)
 * - **Control Flow**: Conditionals, loops, branches
 * - **Error Handling**: Try/catch blocks
 * - **State Management**: State accumulation and context exports
 * - **Integration**: Complex end-to-end workflows
 *
 * ## Usage
 *
 * ```kotlin
 * class CompleteOrchestratorTest : AbstractOrchestratorTest() {
 *     override suspend fun executeWorkflow(...): JsonElement {
 *         val node = getWorkflowNode(yaml, namespace, name, version)
 *         return CompleteOrchestrator.run(node, input)
 *     }
 * }
 * ```
 */
@ExperimentalTime
abstract class AbstractOrchestratorTest : FunSpec() {

    init {
        afterEach {
            DefinitionCache.clear()
        }

        // ========================================
        // Basic Execution Tests
        // ========================================

        test("should execute simple workflow with set tasks and then directive") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(10, output["result"]?.jsonPrimitive?.int)
        }

        test("should execute workflow with data transformations") {
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
                              sum: ${ .sum + $item }
                      output:
                        as: ${ . }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(15, output["sum"]?.jsonPrimitive?.int)
        }

        // ========================================
        // Activity Execution Tests
        // ========================================

        test("should execute HTTP call activity completely") {
            val yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Should complete the HTTP call and return the result
            assertEquals(1, output["id"]?.jsonPrimitive?.int)
            assertTrue(output.containsKey("title"))
            assertTrue(output.containsKey("body"))
        }

        test("should execute shell command activity completely") {
            val yaml = """
                do:
                  - echoCommand:
                      run:
                        shell:
                          command: echo "Hello World"
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            // Should complete the shell command and return stdout as JsonPrimitive
            assertEquals("Hello World", (output as JsonPrimitive).content)
        }

        test("should execute multiple activities in sequence") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Should complete both HTTP calls
            assertEquals(2, output["id"]?.jsonPrimitive?.int)
        }

        // ========================================
        // Delay/Wait Tests
        // ========================================

        test("should handle wait task and continue execution") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Should execute tasks before and after wait
            assertNull(output["before"])
            assertEquals(JsonPrimitive(true), output["after"])
        }

        // ========================================
        // Sub-workflow Tests
        // ========================================

        test("should execute sub-workflow inline and wait for completion") {
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
            val output = executeWorkflow(parentYaml, JsonObject(emptyMap())) as JsonObject

            // Should complete child workflow and return result to parent
            assertEquals(10, output["result"]?.jsonPrimitive?.int)
        }

        test("should execute fire-and-forget sub-workflow asynchronously") {
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
            """
            val output = executeWorkflow(parentYaml, JsonObject(emptyMap())) as JsonObject

            // Parent should complete immediately without waiting for child
            assertNull(output["value"])
            assertEquals(JsonPrimitive("test"), output["data"])
        }

        test("should execute nested sub-workflows recursively") {
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
            val output = executeWorkflow(parentYaml, JsonObject(emptyMap())) as JsonObject

            // Should complete all nested workflows
            assertEquals(1, output["level"]?.jsonPrimitive?.int)
        }

        // ========================================
        // Control Flow Tests
        // ========================================

        test("should handle conditional branches") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("condition was true", output["result"]?.jsonPrimitive?.content)
        }

        test("should handle loops completely") {
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
                              sum: ${ .sum + $item }
                      output:
                        as: ${ . }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(6, output["sum"]?.jsonPrimitive?.int)
        }

        // ========================================
        // Error Handling Tests
        // ========================================

        test("should handle try-catch blocks") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["errorHandled"]?.jsonPrimitive?.boolean)
        }

        // ========================================
        // State Management Tests
        // ========================================

        test("should maintain workflow state correctly") {
            val yaml = $$"""
                do:
                  - step1:
                      set:
                        a: 1
                        b: 2
                  - step2:
                      set:
                        a: ${ .a }
                        c: ${ .a + .b }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Should accumulate state through execution and produce correct output
            assertEquals(JsonPrimitive(1), output["a"])
            assertNull(output["b"])
            assertEquals(JsonPrimitive(3), output["c"])
        }

        test("should handle exported context correctly") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(JsonPrimitive(3), output["result"])
        }

        // ========================================
        // Integration Tests
        // ========================================

        test("should execute complex workflow end-to-end") {
            val yaml = $$"""
                do:
                  - initialize:
                      set:
                        values: ${ [10, 20, 30] }
                        sum: 0
                  - sumLoop:
                      for:
                        in: ${ .values }
                      do:
                        - add:
                            set:
                              sum: ${ .sum + $item }
                        - waiting:
                            wait:
                              milliseconds: 10
                      output:
                        as: ${ . }
                  - checkSuccess:
                      if: ${ .sum == 60 }
                      set:
                        status: "success"
                        sum: ${ .sum }
                      then: exit
                  - checkFailure:
                      if: ${ .sum != 60 }
                      set:
                        status: "failure"
                        sum: ${ .sum }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("success", output["status"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive(60), output["sum"])
        }
    }

    /**
     * Execute a workflow from start to completion and return the final output.
     *
     * Subclasses implement this method to adapt their orchestrator's execution model:
     * - **CompleteOrchestrator**: Call `run()` directly and return output
     * - **PausableOrchestrator**: Loop through pause results until completion
     *
     * @param yaml The workflow definition in YAML format
     * @param input The initial input dataset
     * @param namespace The workflow namespace (default: "default")
     * @param name The workflow name (default: "test")
     * @param version The workflow version (default: "0.1.0")
     * @return The final workflow output
     */
    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
