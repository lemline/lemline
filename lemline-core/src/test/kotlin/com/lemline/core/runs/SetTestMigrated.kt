// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.execution.ExecutionOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Test

/**
 * Migrated version of SetTest using the new ExecutionOrchestrator implementation.
 * This verifies that the old test cases still work with the new execution model.
 */
@ExperimentalTime
class SetTestMigrated {

    @Test
    fun `set task sets output`() = runTest {
        val doYaml = $$"""
           do:
             - first:
                set:
                  counter: 0
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(mapOf()))

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonObject(mapOf("counter" to JsonPrimitive(0))), // expected
            output, // actual
        )
    }

    @Test
    fun `set task does not use expression by default`() = runTest {
        val doYaml = $$"""
           do:
             - first:
                set:
                  counter:  .
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(1))

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonObject(mapOf("counter" to JsonPrimitive("."))), // expected
            output, // actual
        )
    }

    @Test
    fun `set task can use expression`() = runTest {
        val doYaml = $$"""
           do:
             - first:
                set:
                  counter: ${ . + 1 }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(1)) as JsonObject

        // Assert the output matches our expected transformed value
        assertEquals(2, output["counter"]?.jsonPrimitive?.int)
    }

    @Test
    fun `set value can be nested`() = runTest {
        val doYaml = $$"""
           do:
             - first:
                set:
                  counter:
                    a: 0
                    b: ${ . }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(1)) as JsonObject

        // Assert the output matches our expected transformed value
        val counter = output["counter"]?.jsonObject
        assertEquals(0, counter?.get("a")?.jsonPrimitive?.int)
        assertEquals(1, counter?.get("b")?.jsonPrimitive?.int)
    }

    @Test
    fun `multiple set tasks`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  set:
                    value: ${ .value + "2" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("123"), // expected
            output, // actual
        )
    }

    @Test
    fun `multiple and nested set tasks`() = runTest {
        val doYaml = $$"""
            do:
              - first:
                  set:
                    value: ${ "1" }
              - second:
                  do:
                    - firstA:
                        set:
                          value: ${ .value + "2a" }
                    - firstB:
                        set:
                          value: ${ .value + "2b" }
              - third:
                  set:
                    value: ${ .value + "3" }
            output:
              as: ${ .value }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(""))

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a2b3"), // expected
            output, // actual
        )
    }
}
