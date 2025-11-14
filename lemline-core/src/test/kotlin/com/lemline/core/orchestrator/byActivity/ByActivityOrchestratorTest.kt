// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.byActivity

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.getWorkflowNode
import com.lemline.core.nodes.NodePosition
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.orchestrator.bases.AbstractOrchestratorTest
import com.lemline.core.orchestrator.executeActivityByActivityWorkflow
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowState
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for PausableOrchestrator including both shared scenarios and pause-specific tests.
 *
 * PausableOrchestrator executes workflows step-by-step and pauses at boundaries:
 * - After activities complete (HTTP, Shell, Script)
 * - When delays are needed (Wait task, retry with backoff)
 * - Before sub-workflows need to be started
 * - Returns PausableResult indicating pause reason or completion
 *
 * This test class extends AbstractOrchestratorTest to verify that PausableOrchestrator
 * produces the same final outputs as CompleteOrchestrator. Additionally, it includes
 * pause-specific tests that verify the orchestrator pauses at the correct boundaries.
 */
@ExperimentalTime
class ByActivityOrchestratorTest : AbstractOrchestratorTest() {

    val executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY

    init {
        afterEach {
            DefinitionCache.clear()
        }
    }

    override suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String
    ): JsonElement = executeActivityByActivityWorkflow(yaml, namespace, name, version, input)

    // ========================================
    // Pause-Specific Tests
    // ========================================
    //
    // These tests verify that PausableOrchestrator pauses at the correct boundaries.
    // They check the pause behavior without running to completion.
    // ========================================

    init {

        // ========================================
        // Activity Pause Tests
        // ========================================

        test("should pause after HTTP activity completes") {
            val yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )

            // Should pause after HTTP call completes
            assertIs<WorkflowState.ReadyForNextTask>(result)

            // Output should contain the HTTP response
            val output = result.nextRawInput as JsonObject
            assertEquals(1, output["id"]?.jsonPrimitive?.content?.toInt())
            assertTrue(output.containsKey("title"))
        }

        test("should pause after shell command completes") {
            val yaml = """
                do:
                  - echoCommand:
                      run:
                        shell:
                          command: echo "Hello World"
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )

            // Should pause after shell command completes
            assertIs<WorkflowState.ReadyForNextTask>(result)

            // Output should be JsonPrimitive with shell stdout
            val output = result.nextRawInput as kotlinx.serialization.json.JsonPrimitive
            assertTrue(output.content.contains("Hello World"))
        }

        // ========================================
        // Delay Pause Tests
        // ========================================

        test("should pause when delay is needed from wait task") {
            val yaml = """
                do:
                  - waitStep:
                      wait:
                        seconds: 5
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )

            // Should pause instead of waiting
            assertIs<WorkflowState.Waiting>(result)
            assertEquals(5.seconds, result.duration)
        }

        test("should pause for millisecond delays") {
            val yaml = """
                do:
                  - shortWait:
                      wait:
                        milliseconds: 100
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )

            assertIs<WorkflowState.Waiting>(result)
            assertEquals(100.milliseconds, result.duration)
        }

        // ========================================
        // Sub-workflow Pause Tests
        // ========================================

        test("should pause before sub-workflow execution") {
            // Define the child workflow that will be called
            val childYaml = """
                do:
                  - process:
                      set:
                        result: "child completed"
            """
            getWorkflowNode(childYaml, namespace = "test", name = "childWorkflow", version = "0.1.0")

            // Parent workflow that calls the child
            val parentYaml = """
                do:
                  - callChild:
                      run:
                        workflow:
                          name: childWorkflow
                          namespace: test
                          version: '0.1.0'
            """
            val rootNode = getWorkflowNode(parentYaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            // Should pause before starting sub-workflow
            assertIs<WorkflowState.RunningChildWorkflow>(result)
            assertEquals("childWorkflow", result.childConfig.name)
            assertEquals("test", result.childConfig.namespace)
            assertEquals("0.1.0", result.childConfig.version)
            assertTrue(result.childConfig.sync) // default is await=true
        }

        test("should pause before fire-and-forget sub-workflow") {
            // Define the child workflow
            val childYaml = """
                do:
                  - process:
                      set:
                        done: true
            """
            getWorkflowNode(childYaml, namespace = "test", name = "asyncChild", version = "0.1.0")

            // Parent with await=false
            val parentYaml = """
                do:
                  - launchChild:
                      run:
                        await: false
                        workflow:
                          name: asyncChild
                          namespace: test
                          version: '0.1.0'
            """
            val rootNode = getWorkflowNode(parentYaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.RunningChildWorkflow>(result)
            assertEquals(false, result.childConfig.sync) // await=false
        }

        // ========================================
        // Completion Tests (No Pause)
        // ========================================

        test("should return complete when workflow finishes without activities") {
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: "done"
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            // Should complete without pausing (no activities)
            assertIs<WorkflowState.Completed>(result)
            val output = result.output as JsonObject
            assertEquals("done", output["result"]?.jsonPrimitive?.content)
        }

        test("should execute non-activity tasks without pausing") {
            val yaml = """
                do:
                  - step1:
                      set:
                        value: 1
                  - step2:
                      set:
                        value: 2
                  - step3:
                      set:
                        value: 3
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            // Should complete all set tasks without pausing
            assertIs<WorkflowState.Completed>(result)
            val output = result.output as JsonObject
            assertEquals(3, output["value"]?.jsonPrimitive?.int)
        }

        // ========================================
        // Sequential Execution Tests
        // ========================================

        test("should pause at first activity in sequence") {
            val yaml = """
                do:
                  - setValue:
                      set:
                        counter: 1
                  - callHttp:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                  - setAfterHttp:
                      set:
                        done: true
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            // Should execute setValue (no pause), then pause at HTTP call
            assertIs<WorkflowState.ReadyForNextTask>(result)

            // States should contain the completed steps
            assertTrue(result.taskStates.isNotEmpty())
        }

        test("should execute multiple non-activity steps before pausing at activity") {
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
                  - callShell:
                      run:
                        shell:
                          command: echo "done"
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            // Should execute all set tasks, then pause after shell command
            assertIs<WorkflowState.ReadyForNextTask>(result)
            val output = result.nextRawInput as kotlinx.serialization.json.JsonPrimitive
            assertTrue(output.content.contains("done"))
        }

        // ========================================
        // State Consistency Tests
        // ========================================

        test("should maintain state consistency at pause point") {
            val yaml = """
                do:
                  - prepareData:
                      set:
                        message: "Calling API"
                  - callApi:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """
            val rootNode = getWorkflowNode(yaml)
            val states = mutableMapOf<NodePosition, TaskState>()
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                taskStates = states,
                executionMode = executionMode
            )

            // Should pause after HTTP call
            assertIs<WorkflowState.ReadyForNextTask>(result)

            // States should be not empty and contain the completed steps
            assertTrue(result.taskStates.isNotEmpty())
        }

        test("should capture all intermediate states at pause point") {
            val yaml = $$"""
                do:
                  - initialize:
                      set:
                        values: [1, 2, 3]
                        sum: 0
                  - process:
                      for:
                        in: ${ .values }
                      do:
                        - transform:
                            set:
                              sum: ${ .sum + $item }
                      output:
                        as: ${ . }
                  - callActivity:
                      run:
                        shell:
                          command: echo "processed"
            """
            val rootNode = getWorkflowNode(yaml)
            val result = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.ReadyForNextTask>(result)
            // All loop iterations and transformations should be in state
            assertNotNull(result.taskStates)
            assertTrue(result.taskStates.isNotEmpty())
        }

        // ========================================
        // Child Workflow Resumption Tests
        // ========================================

        test("should resume parent workflow after await=true child completes") {
            // Define the child workflow
            val childYaml = $$"""
                do:
                  - double:
                      set:
                        result: ${ .value * 2 }
            """
            getWorkflowNode(childYaml, namespace = "test", name = "doubler", version = "0.1.0")

            // Parent workflow that calls the child with await=true
            val parentYaml = $$"""
                do:
                  - callChild:
                      run:
                        workflow:
                          namespace: test
                          name: doubler
                          version: '0.1.0'
                          input:
                            value: 5
                  - useChildResult:
                      set:
                        result: ${ .result * 2 }
                        final: true
            """
            val rootNode = getWorkflowNode(parentYaml)

            // First execution - should pause at sub-workflow
            val pauseResult = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.RunningChildWorkflow>(pauseResult)
            assertEquals("doubler", pauseResult.childConfig.name)
            assertTrue(pauseResult.childConfig.sync)

            // Simulate child workflow execution (would be done by runner)
            val childOutput = kotlinx.serialization.json.buildJsonObject {
                put("result", kotlinx.serialization.json.JsonPrimitive(10))
            }

            // Resume parent with child output
            // Navigate to the actual task node: rootNode -> do block -> callChild task
            val doNode = rootNode.children!!.first()
            val callChildNode = doNode.children!!.first()
            val resumeResult = WorkflowOrchestrator.resumeFromInterruptedTask(
                node = callChildNode,
                rawOutput = childOutput,
                taskStates = pauseResult.taskStates.toMutableMap(),
                executionMode = executionMode
            )

            // Should complete the parent workflow
            assertIs<WorkflowState.Completed>(resumeResult)
            val output = resumeResult.output as JsonObject
            assertEquals(20, output["result"]?.jsonPrimitive?.int)
            assertEquals(true, output["final"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("should resume parent workflow after await=false child starts (fire-and-forget)") {
            // Define the child workflow
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
                        data: test
                  - launchChild:
                      run:
                        await: false
                        workflow:
                          namespace: test
                          name: processor
                          version: '0.1.0'
                          input:
                            value: subInput
                  - continueParent:
                      set:
                        value: ${ .data + .value }
                        parentDone: true
            """
            val rootNode = getWorkflowNode(parentYaml)

            // First execution - should pause at sub-workflow
            val pauseResult = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.RunningChildWorkflow>(pauseResult)
            assertEquals(false, pauseResult.childConfig.sync)

            // For await=false, parent continues immediately with the child's input
            // (child runs independently in background, parent doesn't wait for child's output)
            // Runner calls resumeFromChildWorkflow immediately after starting the child
            val doNode = rootNode.children!!.first()
            val launchChildNode = doNode.children!![1]
            val resumeResult = WorkflowOrchestrator.resumeFromInterruptedTask(
                node = launchChildNode,
                rawOutput = pauseResult.rawOutput!!,  // run workflow node input
                taskStates = pauseResult.taskStates.toMutableMap(),
                executionMode = executionMode
            )

            // Should complete the parent workflow
            assertIs<WorkflowState.Completed>(resumeResult)
            val output = resumeResult.output as JsonObject
            assertEquals(true, output["parentDone"]?.jsonPrimitive?.content?.toBoolean())
            // Note: For await=false, parent continues with child's input, which is {"value": "test"}
            assertEquals("test", output["value"]?.jsonPrimitive?.content)
        }

        test("should resume parent and hit another pause after child completes") {
            // Define the child workflow
            val childYaml = $$"""
                do:
                  - increment:
                      set:
                        count: ${ .count + 1 }
            """
            getWorkflowNode(childYaml, namespace = "test", name = "incrementer", version = "0.1.0")

            // Parent workflow with activity after child
            val parentYaml = """
                do:
                  - initialize:
                      set:
                        count: 0
                  - callChild:
                      run:
                        workflow:
                          namespace: test
                          name: incrementer
                          version: '0.1.0'
                  - callHttp:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """
            val rootNode = getWorkflowNode(parentYaml)

            // First execution - pause at sub-workflow
            val pauseResult1 = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.RunningChildWorkflow>(pauseResult1)

            // Child completes
            val childOutput = kotlinx.serialization.json.buildJsonObject {
                put("count", kotlinx.serialization.json.JsonPrimitive(1))
            }

            // Resume parent - should hit HTTP activity and pause again
            // Navigate to the actual task node: rootNode -> do block -> callChild task (index 1)
            val doNode = rootNode.children!!.first()
            val callChildNode = doNode.children!![1]
            val pauseResult2 = WorkflowOrchestrator.resumeFromInterruptedTask(
                node = callChildNode,
                rawOutput = childOutput,
                taskStates = pauseResult1.taskStates.toMutableMap(),
                executionMode = executionMode
            )

            // Should pause at HTTP activity
            assertIs<WorkflowState.ReadyForNextTask>(pauseResult2)
            val output = pauseResult2.nextRawInput as JsonObject
            assertEquals(1, output["id"]?.jsonPrimitive?.int)
        }

        test("should handle nested child workflows with resumption") {
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

            // Execute parent - should pause at child
            val parentPause = WorkflowOrchestrator.resumeFromTask(
                node = rootNode,
                rawInput = JsonObject(emptyMap()),
                executionMode = executionMode
            )
            assertIs<WorkflowState.RunningChildWorkflow>(parentPause)
            assertEquals("child", parentPause.childConfig.name)

            // Simulate child execution (which would also pause at grandchild, but we're simulating the final result)
            val childOutput = kotlinx.serialization.json.buildJsonObject {
                put("level", kotlinx.serialization.json.JsonPrimitive(2))
            }

            // Resume parent with child output
            // Navigate to the actual task node: rootNode -> do block -> callChild task
            val doNode = rootNode.children!!.first()
            val callChildNode = doNode.children!!.first()
            val resumeResult = WorkflowOrchestrator.resumeFromInterruptedTask(
                node = callChildNode,
                rawOutput = childOutput,
                taskStates = parentPause.taskStates.toMutableMap(),
                executionMode = executionMode
            )

            // Should complete parent workflow
            assertIs<WorkflowState.Completed>(resumeResult)
            val output = resumeResult.output as JsonObject
            assertEquals(1, output["level"]?.jsonPrimitive?.int)
        }
    }
}
