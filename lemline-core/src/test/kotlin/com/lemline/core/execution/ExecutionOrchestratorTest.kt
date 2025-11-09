// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.execution.nodes.buildNodeInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach

/**
 * Unit tests for the functional workflow execution model.
 *
 * Tests the ExecutionOrchestrator with simple workflows using DoTask and SetTask.
 */
class ExecutionOrchestratorTest {

    @AfterEach
    fun cleanup() {
        DefinitionCache.clear()
    }

    @Test
    fun `test simple set task workflow`() = runTest {
        // Define workflow: just a single SetTask
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: simple-set
              version: 0.1.0
            do:
              - setStatus:
                  set:
                    status: '"processed"'
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("simple-set"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance from the root node
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with empty input
        val input = buildJsonObject { }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output contains the status field
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("status"))
        assertEquals("processed", LemlineJson.decodeFromElement<String>(outputObj["status"]!!))
    }

    @Test
    fun `test do task with sequential set tasks`() = runTest {
        // Define workflow: Do with two SetTasks in sequence
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: sequential-set
              version: 0.1.0
            do:
              - setStatus:
                  set:
                    status: '"processed"'
              - setTimestamp:
                  set:
                    status: .status
                    timestamp: '"2025-01-08"'
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("sequential-set"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with empty input
        val input = buildJsonObject { }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output contains both fields (merged sequentially)
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("status"))
        assertTrue(outputObj.containsKey("timestamp"))
        assertEquals("processed", LemlineJson.decodeFromElement<String>(outputObj["status"]!!))
        assertEquals("2025-01-08", LemlineJson.decodeFromElement<String>(outputObj["timestamp"]!!))
    }

    @Test
    fun `test set task with expression evaluation`() = runTest {
        // Define workflow: SetTask that references input
        val workflowYaml = $$"""
            document:
              dsl: '1.0.0'
              namespace: test
              name: set-with-expression
              version: 0.1.0
            do:
              - setResult:
                  set:
                    result:
                      inputValue: $input.inputField
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("set-with-expression"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with input containing inputField
        val input = buildJsonObject {
            put("inputField", "testValue")
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output contains the result with evaluated expression
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("result"))
        val result = LemlineJson.decodeFromElement<JsonObject>(outputObj["result"]!!)
        assertTrue(result.containsKey("inputValue"))
        assertEquals("testValue", LemlineJson.decodeFromElement<String>(result["inputValue"]!!))
    }

    @Test
    fun `test for task simple iteration`() = runTest {
        // Define workflow: ForTask that iterates over an array
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: for-iteration
              version: 0.1.0
            do:
              - processItems:
                  for:
                    each: item
                    in: .items
                  do:
                    - addProcessed:
                        set:
                          processed: true
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("for-iteration"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with input containing an array
        val input = buildJsonObject {
            put(
                "items", kotlinx.serialization.json.JsonArray(
                    listOf(
                        buildJsonObject { put("id", 1) },
                        buildJsonObject { put("id", 2) },
                        buildJsonObject { put("id", 3) }
                    )))
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - last iteration should have processed=true
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("processed"))
        assertEquals(true, LemlineJson.decodeFromElement<Boolean>(outputObj["processed"]!!))
    }

    @Test
    fun `test for task with scope variables`() = runTest {
        // Define workflow: ForTask that uses $item and $index scope variables
        val workflowYaml = $$"""
            document:
              dsl: '1.0.0'
              namespace: test
              name: for-with-scope
              version: 0.1.0
            do:
              - processNumbers:
                  for:
                    each: num
                    at: idx
                    in: .numbers
                  do:
                    - collectData:
                        set:
                          lastNumber: $num
                          lastIndex: $idx
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("for-with-scope"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with input containing an array of numbers
        val input = buildJsonObject {
            put(
                "numbers", kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive(10),
                        kotlinx.serialization.json.JsonPrimitive(20),
                        kotlinx.serialization.json.JsonPrimitive(30)
                    )
                )
            )
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - should have last item (30) and last index (2)
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("lastNumber"))
        assertTrue(outputObj.containsKey("lastIndex"))
        assertEquals(30, LemlineJson.decodeFromElement<Int>(outputObj["lastNumber"]!!))
        assertEquals(2, LemlineJson.decodeFromElement<Int>(outputObj["lastIndex"]!!))
    }

    @Test
    fun `test for task with empty collection`() = runTest {
        // Define workflow: ForTask with empty collection
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: for-empty
              version: 0.1.0
            do:
              - processEmpty:
                  for:
                    each: item
                    in: .items
                  do:
                    - shouldNotRun:
                        set:
                          ran: true
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("for-empty"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with empty array
        val input = buildJsonObject {
            put("items", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - should NOT have 'ran' since loop body never executed
        val outputObj = output as JsonObject
        assertTrue(!outputObj.containsKey("ran"))
    }

    @Test
    fun `test switch task with first case match`() = runTest {
        // Define workflow: SwitchTask that matches first case
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: switch-first
              version: 0.1.0
            do:
              - routeOrder:
                  switch:
                    - urgent:
                        when: .priority == "urgent"
                        then: handleUrgent
                    - normal:
                        when: .priority == "normal"
                        then: handleNormal
              - handleUrgent:
                  set:
                    status: '"urgent"'
                  then: exit
              - handleNormal:
                  set:
                    status: '"normal"'
                  then: exit
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("switch-first"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with priority=urgent
        val input = buildJsonObject {
            put("priority", "urgent")
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - should have status="urgent"
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("status"))
        assertEquals("urgent", LemlineJson.decodeFromElement<String>(outputObj["status"]!!))
    }

    @Test
    fun `test switch task with second case match`() = runTest {
        // Define workflow: SwitchTask that matches second case
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: switch-second
              version: 0.1.0
            do:
              - routeOrder:
                  switch:
                    - urgent:
                        when: .priority == "urgent"
                        then: handleUrgent
                    - normal:
                        when: .priority == "normal"
                        then: handleNormal
              - handleUrgent:
                  set:
                    status: '"urgent"'
                  then: exit
              - handleNormal:
                  set:
                    status: '"normal"'
                  then: exit
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("switch-second"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with priority=normal
        val input = buildJsonObject {
            put("priority", "normal")
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - should have status="normal"
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("status"))
        assertEquals("normal", LemlineJson.decodeFromElement<String>(outputObj["status"]!!))
    }

    @Test
    fun `test switch task with default case`() = runTest {
        // Define workflow: SwitchTask with default case (no when condition)
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: switch-default
              version: 0.1.0
            do:
              - routeOrder:
                  switch:
                    - urgent:
                        when: .priority == "urgent"
                        then: handleUrgent
                    - default:
                        then: handleDefault
              - handleUrgent:
                  set:
                    status: '"urgent"'
                  then: exit
              - handleDefault:
                  set:
                    status: '"default"'
                  then: exit
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            WorkflowNamespace("test"),
            WorkflowName("switch-default"),
            WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with priority=low (doesn't match first case)
        val input = buildJsonObject {
            put("priority", "low")
        }
        val output = ExecutionOrchestrator.run(rootInstance, input)

        // Verify output - should have status="default"
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("status"))
        assertEquals("default", LemlineJson.decodeFromElement<String>(outputObj["status"]!!))
    }
}
