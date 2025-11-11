// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for if conditions in task execution using CompleteOrchestrator.
 *
 * Tests conditional task execution based on if expressions,
 * verifying proper scope access and skipping logic.
 */
@ExperimentalTime
class IfConditionExecutionTest {

    @Test
    fun `task with if condition executes when condition is true`() = runTest {
        val yaml = $$"""
            do:
              - conditionalTask:
                  if: ${ . > 50 }
                  set:
                    result: ${ "executed" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(75)) as JsonObject

        assertEquals("executed", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `task with if condition skips when condition is false`() = runTest {
        val yaml = $$"""
            do:
              - conditionalTask:
                  if: ${ . > 100 }
                  set:
                    result: ${ "executed" }
              - alwaysTask:
                  set:
                    fallback: ${ "always" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(50)) as JsonObject

        assertNull(output["result"])
        assertEquals("always", output["fallback"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition can access previous task data`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    score: ${ . }
                    threshold: 80
              - check:
                  if: ${ .score > .threshold }
                  set:
                    status: ${ "high" }
              - fallback:
                  if: ${ .score <= .threshold }
                  set:
                    status: ${ "normal" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(90)) as JsonObject

        assertEquals("high", output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition with string comparison`() = runTest {
        val yaml = $$"""
            do:
              - checkActive:
                  if: ${ . == "active" }
                  set:
                    status: ${ "running" }
              - checkInactive:
                  if: ${ . == "inactive" }
                  set:
                    status: ${ "stopped" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive("active")) as JsonObject

        assertEquals("running", output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition with logical AND`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    value: 75
                    enabled: true
              - check:
                  if: ${ .value > 50 and .enabled == true }
                  set:
                    result: ${ "passed" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("passed", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition with logical OR`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    isAdmin: false
                    isModerator: true
              - check:
                  if: ${ .isAdmin or .isModerator }
                  set:
                    access: ${ "granted" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("granted", output["access"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition can check null values`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    data:
                      value: 42
              - checkExists:
                  if: ${ .data.value != null }
                  set:
                    exists: ${ true }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["exists"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `if condition in for loop`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    evens: []
              - iterate:
                  for:
                    in: ${ [1, 2, 3, 4, 5, 6, 7, 8] }
                  do:
                    - checkEven:
                        if: ${ @item % 2 == 0 }
                        set:
                          evens: ${ .evens + [@item] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val evens = output["evens"]?.jsonArray
        assertEquals(4, evens?.size)
        assertEquals(2, evens?.get(0)?.jsonPrimitive?.int)
        assertEquals(4, evens?.get(1)?.jsonPrimitive?.int)
        assertEquals(6, evens?.get(2)?.jsonPrimitive?.int)
        assertEquals(8, evens?.get(3)?.jsonPrimitive?.int)
    }

    @Test
    fun `if condition can access task metadata`() = runTest {
        val yaml = $$"""
            do:
              - myTask:
                  if: ${ @task.name == "myTask" }
                  set:
                    matched: ${ true }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["matched"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `multiple if conditions in sequence`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    score: 85
              - checkA:
                  if: ${ .score >= 90 }
                  set:
                    grade: ${ "A" }
              - checkB:
                  if: ${ .score >= 80 and .score < 90 }
                  set:
                    grade: ${ "B" }
              - checkC:
                  if: ${ .score >= 70 and .score < 80 }
                  set:
                    grade: ${ "C" }
              - checkF:
                  if: ${ .score < 70 }
                  set:
                    grade: ${ "F" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("B", output["grade"]?.jsonPrimitive?.content)
    }

    @Test
    fun `if condition with complex nested expressions`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    user:
                      role: "admin"
                      level: 5
                      verified: true
              - check:
                  if: ${ (.user.role == "admin" and .user.level > 3) or .user.verified }
                  set:
                    authorized: ${ true }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["authorized"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `if condition preserves data when skipped`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    value: 10
              - conditionalModify:
                  if: ${ .value > 100 }
                  set:
                    value: 999
              - final:
                  set:
                    result: ${ .value }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonPrimitive(50)) as JsonObject

        // Value should remain 10 since conditionalModify was skipped
        assertEquals(10, output["result"]?.jsonPrimitive?.int)
    }
}
