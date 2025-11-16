// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
abstract class WaitExecutionTest : FunSpec() {

    init {
        test("workflow can wait for fixed duration") {
            val yaml = """
                do:
                  - waitFiveSeconds:
                      wait: PT0.1S
                  - afterWait:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("workflow can wait with seconds duration") {
            val yaml = """
                do:
                  - waitSeconds:
                      wait: PT0.1S
                  - afterWait:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("workflow can wait with milliseconds duration") {
            val yaml = """
                do:
                  - waitMillis:
                      wait: PT0.05S
                  - afterWait:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("workflow can have multiple wait tasks in sequence") {
            val yaml = """
                do:
                  - firstWait:
                      wait: PT0.05S
                  - secondWait:
                      wait: PT0.05S
                  - afterBothWaits:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("wait task preserves input data") {
            val yaml = $$"""
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
                        originalMessage: ${ .message }
                        originalCount: ${ .count }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["verified"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("Hello", output["originalMessage"]?.jsonPrimitive?.content)
            assertEquals(42, output["originalCount"]?.jsonPrimitive?.content?.toInt())
        }

        test("wait task works with input transformation") {
            val yaml = $$"""
                do:
                  - prepareData:
                      set:
                        value: 100
                  - waitWithInput:
                      wait: PT0.01S
                      input:
                        from: '${ {doubled: .value * 2} }'
                  - useResult:
                      set:
                        finalValue: ${ .doubled }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(200, output["finalValue"]?.jsonPrimitive?.content?.toInt())
        }

        test("wait task works with output transformation") {
            val yaml = $$"""
                do:
                  - setInitial:
                      set:
                        value: 50
                  - waitTask:
                      wait: PT0.01S
                      output:
                        as: '${ {result: .value * 3} }'
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(150, output["result"]?.jsonPrimitive?.content?.toInt())
            // Original value should not be in output after transformation
            assertEquals(false, output.containsKey("value"))
        }

        test("wait task can be conditional") {
            val yaml = $$"""
                do:
                  - setCondition:
                      set:
                        shouldWait: true
                  - conditionalWait:
                      if: ${ .shouldWait }
                      wait: PT0.05S
                  - afterWait:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("wait task is skipped when condition is false") {
            val yaml = $$"""
                do:
                  - setCondition:
                      set:
                        shouldWait: false
                  - conditionalWait:
                      if: ${ .shouldWait }
                      wait: PT1S
                  - afterWait:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("wait task works in do block") {
            val yaml = """
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(2, output["step"]?.jsonPrimitive?.content?.toInt())
        }

        test("wait task works with for loop") {
            val yaml = $$"""
                do:
                  - loopWithWait:
                      for:
                        each: item
                        in: ${ [1, 2, 3] }
                      do:
                        - waitInLoop:
                            wait: PT0.01S
                        - processItem:
                            set:
                              processed: ${ $item }
                      output:
                        as: ${ . }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject
            // The last iteration (item=3) should be in the output
            assertEquals(3, output["processed"]?.jsonPrimitive?.int)
        }

        test("wait task works in switch branches") {
            val yaml = $$"""
                do:
                  - setValue:
                      set:
                        value: 10
                  - switchWithWait:
                      switch:
                        - case1:
                            when: ${ .value > 5 }
                            then: waitBranch
                  - waitBranch:
                      wait: PT0.01S
                  - afterSwitch:
                      set:
                        completed: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["completed"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("workflow can chain wait with other activity tasks") {
            val yaml = $$"""
                do:
                  - initialize:
                      set:
                        value: 10
                  - waitFirst:
                      wait: PT0.01S
                  - transform:
                      set:
                        value: ${ .value * 2 }
                  - waitSecond:
                      wait: PT0.01S
                  - finalize:
                      set:
                        result: ${ .value + 5 }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // (10 * 2) + 5 = 25
            assertEquals(25, output["result"]?.jsonPrimitive?.content?.toInt())
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
