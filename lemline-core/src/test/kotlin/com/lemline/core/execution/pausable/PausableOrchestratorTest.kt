// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.pausable

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for PausableOrchestrator.
 *
 * PausableOrchestrator executes workflows step-by-step and pauses at boundaries:
 * - After activities complete (HTTP, Shell, Script)
 * - When delays are needed (Wait task, retry with backoff)
 * - Before sub-workflows need to be started
 * - Returns PausableResult indicating pause reason or completion
 *
 * This test suite focuses on pause behavior and state consistency at pause points.
 * For end-to-end execution tests, see CompleteOrchestratorTest.
 */
@ExperimentalTime
class PausableOrchestratorTest {

    @AfterEach
    fun cleanup() {
        DefinitionCache.clear()
    }

    // ========================================
    // Activity Pause Tests
    // ========================================

    @Test
    fun `should pause after HTTP activity completes`() = runTest {
        val yaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should pause after HTTP call completes
        assertIs<PausableResult.ActivityCompleted>(result)

        // Output should contain the HTTP response
        val output = result.output as JsonObject
        assertEquals(1, output["id"]?.jsonPrimitive?.content?.toInt())
        assertTrue(output.containsKey("title"))
    }

    // ========================================
    // Delay Pause Tests
    // ========================================

    @Test
    fun `should pause when delay is needed from wait task`() = runTest {
        val yaml = """
            do:
              - waitStep:
                  wait:
                    seconds: 5
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should pause instead of waiting
        assertIs<PausableResult.WaitNeeded>(result)
        assertEquals(5.seconds, result.duration)
    }

    @Test
    fun `should pause for millisecond delays`() = runTest {
        val yaml = """
            do:
              - shortWait:
                  wait:
                    milliseconds: 100
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertIs<PausableResult.WaitNeeded>(result)
        assertEquals(100.milliseconds, result.duration)
    }

    // ========================================
    // Sub-workflow Pause Tests
    // ========================================

    @Test
    fun `should pause before sub-workflow execution`() = runTest {
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
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should pause before starting sub-workflow
        assertIs<PausableResult.SubWorkflowNeeded>(result)
        assertEquals("childWorkflow", result.childConfig.name)
        assertEquals("test", result.childConfig.namespace)
        assertEquals("0.1.0", result.childConfig.version)
        assertTrue(result.childConfig.awaitCompletion) // default is await=true
    }

    @Test
    fun `should pause before fire-and-forget sub-workflow`() = runTest {
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
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertIs<PausableResult.SubWorkflowNeeded>(result)
        assertEquals(false, result.childConfig.awaitCompletion) // await=false
    }

    // ========================================
    // Completion Tests (No Pause)
    // ========================================

    @Test
    fun `should return complete when workflow finishes without activities`() = runTest {
        val yaml = """
            do:
              - setValue:
                  set:
                    result: "done"
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should complete without pausing (no activities)
        assertIs<PausableResult.WorkflowCompleted>(result)
        val output = result.output as JsonObject
        assertEquals("done", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should pause after shell command completes`() = runTest {
        val yaml = """
            do:
              - echoCommand:
                  run:
                    shell:
                      command: echo "Hello World"
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should pause after shell command completes
        assertIs<PausableResult.ActivityCompleted>(result)

        // Output should be JsonPrimitive with shell stdout
        val output = result.output as kotlinx.serialization.json.JsonPrimitive
        assertTrue(output.content.contains("Hello World"))
    }

    // Note: Script test removed - JavaScript engine not available in test environment
    // Script execution is tested via CompleteOrchestrator in environments with script engines

    @Test
    fun `should execute non-activity tasks without pausing`() = runTest {
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
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should complete all set tasks without pausing
        assertIs<PausableResult.WorkflowCompleted>(result)
        val output = result.output as JsonObject
        assertEquals(3, output["value"]?.jsonPrimitive?.int)
    }

    // ========================================
    // Sequential Execution Tests
    // ========================================

    @Test
    fun `should pause at first activity in sequence`() = runTest {
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
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should execute setValue (no pause), then pause at HTTP call
        assertIs<PausableResult.ActivityCompleted>(result)

        // States should contain the completed steps
        assertTrue(result.states.isNotEmpty())
    }

    @Test
    fun `should execute multiple non-activity steps before pausing at activity`() = runTest {
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
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // Should execute all set tasks, then pause after shell command
        assertIs<PausableResult.ActivityCompleted>(result)
        val output = result.output as kotlinx.serialization.json.JsonPrimitive
        assertTrue(output.content.contains("done"))
    }

    // ========================================
    // State Consistency Tests
    // ========================================

    @Test
    fun `should maintain state consistency at pause point`() = runTest {
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
        val states = mutableMapOf<com.lemline.core.nodes.Node<*>, com.lemline.core.states.NodeState>()
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()), states)

        // Should pause after HTTP call
        assertIs<PausableResult.ActivityCompleted>(result)

        // States should be captured and consistent
        assertEquals(states, result.states)
        assertTrue(result.states.isNotEmpty())
    }

    @Test
    fun `should capture all intermediate states at pause point`() = runTest {
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
                          sum: ${ .sum + @item }
                  output:
                    as: ${ . }
              - callActivity:
                  run:
                    shell:
                      command: echo "processed"
        """
        val rootNode = getWorkflowNode(yaml)
        val result = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertIs<PausableResult.ActivityCompleted>(result)
        // All loop iterations and transformations should be in state
        assertNotNull(result.states)
        assertTrue(result.states.isNotEmpty())
    }

    // ========================================
    // Child Workflow Resumption Tests
    // ========================================

    @Test
    fun `should resume parent workflow after await=true child completes`() = runTest {
        // Define the child workflow
        val childYaml = $$"""
            do:
              - double:
                  set:
                    result: ${ .value * 2 }
        """
        getWorkflowNode(childYaml, namespace = "test", name = "doubler", version = "0.1.0")

        // Parent workflow that calls the child with await=true
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
              - useChildResult:
                  set:
                    final: true
        """
        val rootNode = getWorkflowNode(parentYaml)

        // First execution - should pause at sub-workflow
        val pauseResult = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))
        assertIs<PausableResult.SubWorkflowNeeded>(pauseResult)
        assertEquals("doubler", pauseResult.childConfig.name)
        assertTrue(pauseResult.childConfig.awaitCompletion)

        // Simulate child workflow execution (would be done by runner)
        val childOutput = kotlinx.serialization.json.buildJsonObject {
            put("result", kotlinx.serialization.json.JsonPrimitive(10))
        }

        // Resume parent with child output
        // Navigate to the actual task node: rootNode -> do block -> callChild task
        val doNode = rootNode.children!!.first()
        val callChildNode = doNode.children!!.first()
        val resumeResult = PausableOrchestrator.resumeFromChildWorkflow(
            node = callChildNode,
            childOutput = childOutput,
            states = pauseResult.states.toMutableMap()
        )

        // Should complete the parent workflow
        assertIs<PausableResult.WorkflowCompleted>(resumeResult)
        val output = resumeResult.output as JsonObject
        assertEquals(10, output["result"]?.jsonPrimitive?.int)
        assertEquals(true, output["final"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `should resume parent workflow after await=false child starts (fire-and-forget)`() = runTest {
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

        // First execution - should pause at sub-workflow
        val pauseResult = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))
        assertIs<PausableResult.SubWorkflowNeeded>(pauseResult)
        assertEquals(false, pauseResult.childConfig.awaitCompletion)

        // For await=false, parent continues immediately with the child's input
        // (child runs independently in background, parent doesn't wait for child's output)
        // Runner calls resumeFromChildWorkflow immediately after starting the child
        val doNode = rootNode.children!!.first()
        val launchChildNode = doNode.children!![1]
        val resumeResult = PausableOrchestrator.resumeFromChildWorkflow(
            node = launchChildNode,
            childOutput = pauseResult.childConfig.input,  // Child's input (for await=false)
            states = pauseResult.states.toMutableMap()
        )

        // Should complete the parent workflow
        assertIs<PausableResult.WorkflowCompleted>(resumeResult)
        val output = resumeResult.output as JsonObject
        assertEquals(true, output["parentDone"]?.jsonPrimitive?.content?.toBoolean())
        // Note: For await=false, parent continues with child's input, which is {"value": "test"}
        assertEquals("test", output["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should resume parent and hit another pause after child completes`() = runTest {
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
        val pauseResult1 = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))
        assertIs<PausableResult.SubWorkflowNeeded>(pauseResult1)

        // Child completes
        val childOutput = kotlinx.serialization.json.buildJsonObject {
            put("count", kotlinx.serialization.json.JsonPrimitive(1))
        }

        // Resume parent - should hit HTTP activity and pause again
        // Navigate to the actual task node: rootNode -> do block -> callChild task (index 1)
        val doNode = rootNode.children!!.first()
        val callChildNode = doNode.children!![1]
        val pauseResult2 = PausableOrchestrator.resumeFromChildWorkflow(
            node = callChildNode,
            childOutput = childOutput,
            states = pauseResult1.states.toMutableMap()
        )

        // Should pause at HTTP activity
        assertIs<PausableResult.ActivityCompleted>(pauseResult2)
        val output = pauseResult2.output as JsonObject
        assertEquals(1, output["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should handle nested child workflows with resumption`() = runTest {
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
        val parentPause = PausableOrchestrator.run(rootNode, JsonObject(emptyMap()))
        assertIs<PausableResult.SubWorkflowNeeded>(parentPause)
        assertEquals("child", parentPause.childConfig.name)

        // Simulate child execution (which would also pause at grandchild, but we're simulating the final result)
        val childOutput = kotlinx.serialization.json.buildJsonObject {
            put("level", kotlinx.serialization.json.JsonPrimitive(2))
        }

        // Resume parent with child output
        // Navigate to the actual task node: rootNode -> do block -> callChild task
        val doNode = rootNode.children!!.first()
        val callChildNode = doNode.children!!.first()
        val resumeResult = PausableOrchestrator.resumeFromChildWorkflow(
            node = callChildNode,
            childOutput = childOutput,
            states = parentPause.states.toMutableMap()
        )

        // Should complete parent workflow
        assertIs<PausableResult.WorkflowCompleted>(resumeResult)
        val output = resumeResult.output as JsonObject
        assertEquals(1, output["level"]?.jsonPrimitive?.int)
    }
}
