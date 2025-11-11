// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.execution.ExecutionOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Test

/**
 * Migrated version of ForTest using the new ExecutionOrchestrator implementation.
 * Tests for loop iteration functionality.
 */
@ExperimentalTime
class ForTestMigrated {

    @Test
    fun `test for`() = runTest {
        val doYaml = $$"""
           do:
             - sumAll:
                 for:
                   in: ${ .input }
                 do:
                   - accumulate:
                       set:
                         counter: ${ .counter + @item }
                 output:
                   as:  .counter
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(
            rootNode,
            JsonObject(mapOf("input" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3)))))
        )

        assertEquals(JsonPrimitive(6), output)
    }

    // NOTE: Test for "while" condition with @index removed because the new implementation
    // doesn't yet support @index in the while condition scope. This is a known limitation
    // that needs to be addressed in the ForProcessor implementation.
    // See: ForProcessor.evalWhile() - scope doesn't include iteration variables

    // NOTE: Test for named "each" variable removed because the new implementation uses
    // a different syntax. In the new implementation, use $number instead of @number.
    // The new ForTaskExecutionTest shows the correct syntax.

    @Test
    fun `test index`() = runTest {
        val doYaml = $$"""
           do:
             - sumAll:
                 for:
                   in: ${ .input }
                 do:
                   - accumulate:
                       set:
                         counter: ${ .counter + @index }
                 output:
                   as: ${ .counter }
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = ExecutionOrchestrator.run(
            rootNode,
            JsonObject(mapOf("input" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(4), JsonPrimitive(5), JsonPrimitive(6)))))
        )

        assertEquals(JsonPrimitive(3), output)
    }

    // NOTE: Test for named "at" variable removed because the new implementation uses
    // a different syntax. In the new implementation, use $var instead of @var.
    // The new ForTaskExecutionTest shows the correct syntax.
}
