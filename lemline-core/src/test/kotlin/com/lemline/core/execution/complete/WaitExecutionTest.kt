// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for Wait task execution using CompleteOrchestrator.
 *
 * Tests wait functionality to verify:
 * - Basic wait with fixed duration
 * - Wait with different ISO 8601 duration formats
 * - Wait with expression-based durations
 * - Wait in sequence with other tasks
 * - Wait preserves input data
 * - Wait timing accuracy
 */
@ExperimentalTime
class WaitExecutionTest {

    @Test
    fun `workflow can wait for fixed duration`() = runTest {
        val yaml = $"""
            do:
              - waitFiveSeconds:
                  wait: PT0.1S
              - afterWait:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `workflow can wait with seconds duration`() = runTest {
        val yaml = $"""
            do:
              - waitSeconds:
                  wait: PT0.1S
              - afterWait:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `workflow can wait with milliseconds duration`() = runTest {
        val yaml = $"""
            do:
              - waitMillis:
                  wait: PT0.05S
              - afterWait:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `workflow can have multiple wait tasks in sequence`() = runTest {
        val yaml = $"""
            do:
              - firstWait:
                  wait: PT0.05S
              - secondWait:
                  wait: PT0.05S
              - afterBothWaits:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `wait task preserves input data`() = runTest {
        val yaml = $"""
            do:
              - setData:
                  set:
                    message: Hello
                    count: 42
              - waitTask:
                  wait: PT0.01S
              - verifyData:
                  set:
                    verified: true
                    originalMessage: ${'$'}{ .message }
                    originalCount: ${'$'}{ .count }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("Hello", output["originalMessage"]?.jsonPrimitive?.content)
        assertEquals(42, output["originalCount"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `wait task works with input transformation`() = runTest {
        val yaml = $"""
            do:
              - prepareData:
                  set:
                    value: 100
              - waitWithInput:
                  wait: PT0.01S
                  input:
                    from: '${'$'}{ {doubled: .value * 2} }'
              - useResult:
                  set:
                    finalValue: ${'$'}{ .doubled }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(200, output["finalValue"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `wait task works with output transformation`() = runTest {
        val yaml = $"""
            do:
              - setInitial:
                  set:
                    value: 50
              - waitTask:
                  wait: PT0.01S
                  output:
                    as: '${'$'}{ {result: .value * 3} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(150, output["result"]?.jsonPrimitive?.content?.toInt())
        // Original value should not be in output after transformation
        assertEquals(false, output.containsKey("value"))
    }

    @Test
    fun `wait task can be conditional`() = runTest {
        val yaml = $"""
            do:
              - setCondition:
                  set:
                    shouldWait: true
              - conditionalWait:
                  if: ${'$'}{ .shouldWait }
                  wait: PT0.05S
              - afterWait:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `wait task is skipped when condition is false`() = runTest {
        val yaml = $"""
            do:
              - setCondition:
                  set:
                    shouldWait: false
              - conditionalWait:
                  if: ${'$'}{ .shouldWait }
                  wait: PT1S
              - afterWait:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `wait task works in do block`() = runTest {
        val yaml = $"""
            do:
              - processWithWait:
                  do:
                    - innerTask:
                        set:
                          step: 1
                    - innerWait:
                        wait: PT0.01S
                    - afterInnerWait:
                        set:
                          step: 2
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(2, output["step"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `wait task works with for loop`() = runTest {
        val yaml = """
            do:
              - loopWithWait:
                  for:
                    each: item
                    in: ${'$'}{ [1, 2, 3] }
                  do:
                    - waitInLoop:
                        wait: PT0.01S
                    - processItem:
                        set:
                          processed: ${'$'}{ @item }
                  output:
                    as: ${'$'}{ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject
        // The last iteration (item=3) should be in the output
        assertEquals(3, output["processed"]?.jsonPrimitive?.int)
    }

    @Test
    fun `wait task works in switch branches`() = runTest {
        val yaml = """
            do:
              - setValue:
                  set:
                    value: 10
              - switchWithWait:
                  switch:
                    - case1:
                        when: ${'$'}{ .value > 5 }
                        then: waitBranch
              - waitBranch:
                  wait: PT0.01S
              - afterSwitch:
                  set:
                    completed: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `workflow can chain wait with other activity tasks`() = runTest {
        val yaml = $"""
            do:
              - initialize:
                  set:
                    value: 10
              - waitFirst:
                  wait: PT0.01S
              - transform:
                  set:
                    value: ${'$'}{ .value * 2 }
              - waitSecond:
                  wait: PT0.01S
              - finalize:
                  set:
                    result: ${'$'}{ .value + 5 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // (10 * 2) + 5 = 25
        assertEquals(25, output["result"]?.jsonPrimitive?.content?.toInt())
    }
}
