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
        val output = ExecutionOrchestrator.execute(rootInstance, input)

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
            com.lemline.common.values.WorkflowNamespace("test"),
            com.lemline.common.values.WorkflowName("sequential-set"),
            com.lemline.common.values.WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with empty input
        val input = buildJsonObject { }
        val output = ExecutionOrchestrator.execute(rootInstance, input)

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
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: set-with-expression
              version: 0.1.0
            do:
              - setResult:
                  set:
                    result:
                      inputValue: ${'$'}input.inputField
        """.trimIndent()

        // Parse workflow definition and build node tree
        DefinitionCache.parseAndPut(workflowYaml)
        val workflow = DefinitionCache.getOrNull(
            com.lemline.common.values.WorkflowNamespace("test"),
            com.lemline.common.values.WorkflowName("set-with-expression"),
            com.lemline.common.values.WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with input containing inputField
        val input = buildJsonObject {
            put("inputField", "testValue")
        }
        val output = ExecutionOrchestrator.execute(rootInstance, input)

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
            com.lemline.common.values.WorkflowNamespace("test"),
            com.lemline.common.values.WorkflowName("for-iteration"),
            com.lemline.common.values.WorkflowVersion("0.1.0")
        )!!
        val rootNode = DefinitionCache.getRootNode(workflow)

        // Build node instance
        val rootInstance = buildNodeInstance(rootNode, null)

        // Execute workflow with input containing an array
        val input = buildJsonObject {
            put("items", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("id", 1) },
                buildJsonObject { put("id", 2) },
                buildJsonObject { put("id", 3) }
            )))
        }
        val output = ExecutionOrchestrator.execute(rootInstance, input)

        // Verify output - last iteration should have processed=true
        val outputObj = output as JsonObject
        assertTrue(outputObj.containsKey("processed"))
        assertEquals(true, LemlineJson.decodeFromElement<Boolean>(outputObj["processed"]!!))
    }
}
