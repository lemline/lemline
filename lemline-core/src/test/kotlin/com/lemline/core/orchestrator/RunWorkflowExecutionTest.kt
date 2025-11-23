// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.InternalException
import com.lemline.core.getWorkflowToTest
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
class RunWorkflowExecutionTest : FunSpec() {

    init {
        afterEach {
            // Clear the definition cache after each test to avoid interference
            DefinitionCache.clear()
        }

        test("workflow can execute simple sub-workflow") {
            // Define a simple child workflow that doubles the input
            val childWorkflowYaml = $$"""
                do:
                  - double:
                      set:
                        result: ${ .value * 2 }
            """

            // Register the child workflow in the cache
            getWorkflowToTest(childWorkflowYaml, namespace = "test", name = "doubler", version = "0.1.0")

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

            val output = executeWorkflow(
                parentWorkflowYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(10, output["result"]?.jsonPrimitive?.int)
        }

        test("workflow can pass input to sub-workflow") {
            // Child workflow that greets by name
            val childWorkflowYaml = $$"""
                do:
                  - greet:
                      set:
                        greeting: ${ "Hello, " + .name + "!" }
            """

            getWorkflowToTest(childWorkflowYaml, namespace = "test", name = "greeter", version = "0.1.0")

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

            val output = executeWorkflow(
                parentWorkflowYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals("Hello, Alice!", output["greeting"]?.jsonPrimitive?.content)
        }

        test("workflow can execute recursive factorial calculation") {
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

            getWorkflowToTest(factorialYaml, namespace = "test", name = "factorial", version = "0.1.0")

            // Call factorial(5)
            val output = executeWorkflow(
                factorialYaml,
                JsonObject(mapOf("n" to JsonPrimitive(5))),
                namespace = "test",
                name = "factorial-main",
                version = "0.1.0"
            ) as JsonObject

            // 5! = 120
            assertEquals(120, output["n"]?.jsonPrimitive?.int)
        }

        test("workflow can call multiple sub-workflows in sequence") {
            // First child workflow
            val adderYaml = $$"""
                do:
                  - add:
                      set:
                        result: ${ .a + .b }
            """
            getWorkflowToTest(adderYaml, namespace = "test", name = "adder", version = "0.1.0")

            // Second child workflow
            val multiplierYaml = $$"""
                do:
                  - multiply:
                      set:
                        result: ${ .value * 2 }
            """
            getWorkflowToTest(multiplierYaml, namespace = "test", name = "multiplier", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            // (3 + 7) * 2 = 20
            assertEquals(20, output["result"]?.jsonPrimitive?.int)
        }

        test("workflow can use sub-workflow output in subsequent tasks") {
            // Child workflow that processes data
            val processorYaml = $$"""
                do:
                  - process:
                      set:
                        processed: true
                        value: ${ .input * 10 }
            """
            getWorkflowToTest(processorYaml, namespace = "test", name = "processor", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(60, output["finalValue"]?.jsonPrimitive?.int)
            assertEquals(true, output["wasProcessed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("workflow output can be transformed with output as") {
            // Child workflow
            val childYaml = """
                do:
                  - compute:
                      set:
                        value: 42
                        status: success
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "child", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(42, output["result"]?.jsonPrimitive?.int)
            assertTrue(!output.containsKey("status"))
        }

        test("workflow can execute sub-workflow with complex input") {
            // Child workflow that processes complex data
            val processorYaml = $$"""
                do:
                  - process:
                      set:
                        fullName: ${ .firstName + " " + .lastName }
                        age: ${ .age }
            """
            getWorkflowToTest(
                processorYaml,
                namespace = "test",
                name = "person-processor",
                version = "0.1.0"
            )

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals("John Doe", output["fullName"]?.jsonPrimitive?.content)
            assertEquals(30, output["age"]?.jsonPrimitive?.int)
        }

        test("workflow can chain multiple nested sub-workflows") {
            // Level 3 workflow
            val level3Yaml = $$"""
                do:
                  - compute:
                      set:
                        result: ${ .value + 1 }
            """
            getWorkflowToTest(level3Yaml, namespace = "test", name = "level3", version = "0.1.0")

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
            getWorkflowToTest(level2Yaml, namespace = "test", name = "level2", version = "0.1.0")

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
            getWorkflowToTest(level1Yaml, namespace = "test", name = "level1", version = "0.1.0")

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

            val output = executeWorkflow(
                rootYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            // ((5 + 3) * 2) + 1 = 17
            assertEquals(17, output["result"]?.jsonPrimitive?.int)
        }

        test("workflow can execute sub-workflow with default input") {
            // Child workflow that uses input or defaults
            val childYaml = """
                do:
                  - setDefaults:
                      set:
                        value: 10
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "defaulter", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(10, output["value"]?.jsonPrimitive?.int)
        }

        test("workflow can execute sub-workflow asynchronously without waiting") {
            // Child workflow
            val childYaml = """
                do:
                  - slowTask:
                      set:
                        completed: true
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "slow-workflow", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            // Parent should have continued immediately, not waiting for child
            assertEquals(true, output["continued"]?.jsonPrimitive?.content?.toBoolean())
            // Child output should NOT be in parent output (fire-and-forget)
            assertEquals(false, output.containsKey("completed"))
        }

        test("workflow can use workflow context in sub-workflow calls") {
            // Child workflow
            val childYaml = $$"""
                do:
                  - compute:
                      set:
                        doubled: ${ .original * 2 }
                        parentValue: ${ .original }
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "context-user", version = "0.1.0")

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

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(30, output["doubled"]?.jsonPrimitive?.int)
            assertEquals(15, output["parentValue"]?.jsonPrimitive?.int)
        }

        test("exception raised in sub-workflow should propagate to parent for sync execution") {
            // Child workflow that raises an error
            val childYaml = """
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/errors/child
                          status: 400
                          title: Validation failed
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "failing-child", version = "0.1.0")

            // Parent workflow that calls the failing child (sync by default)
            val parentYaml = """
                do:
                  - callFailingChild:
                      run:
                        workflow:
                          namespace: test
                          name: failing-child
                          version: '0.1.0'
            """

            val exception = shouldThrow<InternalException> {
                executeWorkflow(
                    parentYaml,
                    namespace = "test",
                    name = "parent",
                    version = "0.1.0"
                )
            }

            assertEquals(400, exception.error.status)
            assertEquals("https://serverlessworkflow.io/errors/child", exception.error.type)
        }

        test("exception raised in sub-workflow should not propagate to parent for async execution") {
            // Child workflow that raises an error
            val childYaml = """
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/errors/child
                          status: 400
                          title: Validation failed
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "failing-child", version = "0.1.0")

            // Parent workflow that calls the failing child (sync by default)
            val parentYaml = """
                do:
                  - callFailingChild:
                      run:
                        await: false
                        workflow:
                          namespace: test
                          name: failing-child
                          version: '0.1.0'
            """

            shouldNotThrowAny {
                executeWorkflow(
                    parentYaml,
                    namespace = "test",
                    name = "parent",
                    version = "0.1.0"
                )
            }
        }

        test("parent workflow can catch exception thrown by sub-workflow") {
            // Child workflow that raises an error
            val childYaml = """
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/errors/not-found
                          status: 404
                          title: Resource not found
            """
            getWorkflowToTest(childYaml, namespace = "test", name = "error-child", version = "0.1.0")

            // Parent workflow that catches the error from sub-workflow
            val parentYaml = $$"""
                do:
                  - tryCallChild:
                      try:
                        - callChild:
                            run:
                              workflow:
                                namespace: test
                                name: error-child
                                version: '0.1.0'
                      catch:
                        as: caught
                        do:
                          - handleError:
                              set:
                                handled: true
                                errorStatus: ${ $caught.status }
                                errorType: ${ $caught.type }
            """

            val output = executeWorkflow(
                parentYaml,
                namespace = "test",
                name = "parent",
                version = "0.1.0"
            ) as JsonObject

            assertEquals(true, output["handled"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals(404, output["errorStatus"]?.jsonPrimitive?.int)
            assertEquals(true, output["errorType"]?.jsonPrimitive?.content?.contains("not-found"))
        }
    }
}
