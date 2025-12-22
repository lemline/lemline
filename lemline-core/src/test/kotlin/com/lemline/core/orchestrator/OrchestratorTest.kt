// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.values.WorkflowId
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.ShellMockMatcher
import com.lemline.core.activities.mock.ShellMockResponse
import com.lemline.core.activities.mock.ShellMockRule
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.getWorkflowToTest
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.workflows.DefinitionCache
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
class OrchestratorTest : FunSpec() {

    @OptIn(ExperimentalTime::class)
    internal suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement = buildJsonObject { },
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0",
        activityExecutor: ActivityExecutor = MockActivityExecutor.empty(),
        cloudEventHook: CloudEventHook = InMemoryCloudEventHook(),
    ): JsonElement {
        val workflow = getWorkflowToTest(yaml, namespace, name, version)

        val startState = StepByStepOrchestrator.initCmd(
            workflowId = WorkflowId.random(),
            workflowInput = input,
            hasWaitingParent = false,
            startedAt = Clock.System.now()
        )

        val outcome = FullOrchestrator.resume(
            workflow = workflow,
            command = startState,
            serde = true,
            activityExecutor = activityExecutor,
            cloudEventHook = cloudEventHook,
            lifecycleHook = LifecycleEventHook.NOOP,
        )

        return outcome.value()
    }

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
            val mockExecutor = MockActivityExecutor(
                MockConfiguration(
                    httpMocks = listOf(
                        HttpMockRule(
                            match = HttpMockMatcher(url = "*jsonplaceholder*", method = "GET"),
                            response = HttpMockResponse(body = buildJsonObject {
                                put("id", 1)
                                put("title", "Test Post")
                                put("body", "Test Body")
                            })
                        )
                    )
                )
            )
            val yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), activityExecutor = mockExecutor) as JsonObject

            // Should complete the HTTP call and return the mocked result
            assertEquals(1, output["id"]?.jsonPrimitive?.int)
            assertTrue(output.containsKey("title"))
            assertTrue(output.containsKey("body"))
        }

        test("should execute shell command activity completely") {
            val mockExecutor = MockActivityExecutor(
                MockConfiguration(
                    shellMocks = listOf(
                        ShellMockRule(
                            match = ShellMockMatcher(command = "echo*"),
                            response = ShellMockResponse(stdout = "Hello World")
                        )
                    )
                )
            )
            val yaml = """
                do:
                  - echoCommand:
                      run:
                        shell:
                          command: echo "Hello World"
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), activityExecutor = mockExecutor)

            // Should complete the shell command and return stdout as JsonPrimitive
            assertEquals("Hello World", (output as JsonPrimitive).content)
        }

        test("should execute multiple activities in sequence") {
            val mockExecutor = MockActivityExecutor(
                MockConfiguration(
                    httpMocks = listOf(
                        HttpMockRule(
                            match = HttpMockMatcher(url = "*posts/1"),
                            response = HttpMockResponse(body = buildJsonObject { put("id", 1) })
                        ),
                        HttpMockRule(
                            match = HttpMockMatcher(url = "*posts/2"),
                            response = HttpMockResponse(body = buildJsonObject { put("id", 2) })
                        )
                    )
                )
            )
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), activityExecutor = mockExecutor) as JsonObject

            // Should complete both HTTP calls (last one wins as output)
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
            getWorkflowToTest(childYaml, namespace = "test", name = "doubler", version = "0.1.0")

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

            getWorkflowToTest(childYaml, namespace = "test", name = "processor", version = "0.1.0")

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
            getWorkflowToTest(grandchildYaml, namespace = "test", name = "grandchild", version = "0.1.0")

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
            getWorkflowToTest(childYaml, namespace = "test", name = "child", version = "0.1.0")

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
        // Fork Tests
        // ========================================

        test("should execute fork task in cooperative mode (compete=false)") {
            val yaml = """
                do:
                  - parallelWork:
                      fork:
                        compete: false
                        branches:
                          - branch1:
                              set:
                                result: "A"
                          - branch2:
                              set:
                                result: "B"
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            // Cooperative mode returns an array with all branch results
            val arr = output as JsonArray
            assertEquals(2, arr.size)
            assertEquals("A", arr[0].jsonObject["result"]?.jsonPrimitive?.content)
            assertEquals("B", arr[1].jsonObject["result"]?.jsonPrimitive?.content)
        }

        test("should execute fork task in compete mode (compete=true)") {
            val yaml = """
                do:
                  - raceWork:
                      fork:
                        compete: true
                        branches:
                          - slowBranch:
                              do:
                                - delay:
                                    wait:
                                      seconds: 1
                                - result:
                                    set:
                                      winner: "slow"
                          - fastBranch:
                              set:
                                winner: "fast"
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // Compete mode returns only the first completed branch result
            assertEquals("fast", output["winner"]?.jsonPrimitive?.content)

            delay(1000)
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

        // ========================================
        // Emit Task Tests
        // ========================================

        test("should execute emit task and continue workflow") {
            val cloudEventHook = InMemoryCloudEventHook()
            val yaml = $$"""
                do:
                  - setValue:
                      set:
                        orderId: "12345"
                  - emitOrderPlaced:
                      emit:
                        event:
                          with:
                            source: https://petstore.com
                            type: com.petstore.order.placed.v1
                            data:
                              orderId: ${ .orderId }
                  - confirmOrder:
                      set:
                        status: "order_emitted"
                        orderId: ${ .orderId }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), cloudEventHook = cloudEventHook) as JsonObject

            // Should continue execution after emit task (fire-and-forget)
            assertEquals("order_emitted", output["status"]?.jsonPrimitive?.content)
            assertEquals("12345", output["orderId"]?.jsonPrimitive?.content)

            // Verify CloudEvent was emitted via hook
            assertEquals(1, cloudEventHook.emittedEvents.size)
            assertEquals("com.petstore.order.placed.v1", cloudEventHook.emittedEvents[0].type)
        }

        test("should emit CloudEvent with literal properties") {
            val cloudEventHook = InMemoryCloudEventHook()
            val yaml = """
                do:
                  - emitEvent:
                      emit:
                        event:
                          with:
                            source: https://example.com
                            type: com.example.test
                            subject: test-subject
                  - setComplete:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), cloudEventHook = cloudEventHook) as JsonObject

            // Should complete after emitting
            assertEquals(true, output["completed"]?.jsonPrimitive?.boolean)

            // Verify CloudEvent properties
            assertEquals(1, cloudEventHook.emittedEvents.size)
            assertEquals("com.example.test", cloudEventHook.emittedEvents[0].type)
            assertEquals("https://example.com", cloudEventHook.emittedEvents[0].source.toString())
            assertEquals("test-subject", cloudEventHook.emittedEvents[0].subject)
        }

        test("should execute multiple emit tasks in sequence") {
            val cloudEventHook = InMemoryCloudEventHook()
            val yaml = """
                do:
                  - emit1:
                      emit:
                        event:
                          with:
                            source: https://example.com
                            type: com.example.event1
                  - emit2:
                      emit:
                        event:
                          with:
                            source: https://example.com
                            type: com.example.event2
                  - done:
                      set:
                        eventCount: 2
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), cloudEventHook = cloudEventHook) as JsonObject

            // Both emit tasks should complete
            assertEquals(2, output["eventCount"]?.jsonPrimitive?.int)

            // Verify both events were emitted
            assertEquals(2, cloudEventHook.emittedEvents.size)
            assertEquals("com.example.event1", cloudEventHook.emittedEvents[0].type)
            assertEquals("com.example.event2", cloudEventHook.emittedEvents[1].type)
        }

        test("should emit task in a loop") {
            val cloudEventHook = InMemoryCloudEventHook()
            val yaml = $$"""
                do:
                  - initialize:
                      set:
                        items: ["a", "b", "c"]
                        count: 0
                  - emitLoop:
                      for:
                        in: ${ .items }
                      do:
                        - emitItem:
                            emit:
                              event:
                                with:
                                  source: https://example.com
                                  type: com.example.item.processed
                                  data:
                                    item: ${ $item }
                        - incrementCount:
                            set:
                              count: ${ .count + 1 }
                      output:
                        as: ${ . }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()), cloudEventHook = cloudEventHook) as JsonObject

            // Should emit for each item
            assertEquals(3, output["count"]?.jsonPrimitive?.int)

            // Verify 3 events were emitted
            assertEquals(3, cloudEventHook.emittedEvents.size)
        }
    }
}
