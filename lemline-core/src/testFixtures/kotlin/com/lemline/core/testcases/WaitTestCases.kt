// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for WaitTask execution.
 * Tests delay handling and execution continuation.
 */
object WaitTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "wait task with milliseconds",
            yaml = """
                do:
                  - before:
                      set:
                        step: 1
                  - waiting:
                      wait:
                        milliseconds: 10
                  - after:
                      set:
                        step: 2
            """.trimIndent(),
            validate = expectOutputMatching("step=2") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["step"]?.jsonPrimitive?.int == 2
            }
        ),

        WorkflowTestCase(
            name = "wait task with seconds",
            yaml = """
                do:
                  - waiting:
                      wait:
                        seconds: 1
                  - done:
                      set:
                        completed: true
            """.trimIndent(),
            validate = expectOutputMatching("completed=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["completed"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "multiple wait tasks in sequence",
            yaml = $$"""
                do:
                  - init:
                      set:
                        counter: 0
                  - wait1:
                      wait:
                        milliseconds: 5
                  - inc1:
                      set:
                        counter: ${ .counter + 1 }
                  - wait2:
                      wait:
                        milliseconds: 5
                  - inc2:
                      set:
                        counter: ${ .counter + 1 }
            """.trimIndent(),
            validate = expectOutputMatching("counter=2") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["counter"]?.jsonPrimitive?.int == 2
            }
        ),

        WorkflowTestCase(
            name = "wait task preserves data",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        value: 42
                  - waiting:
                      wait:
                        milliseconds: 10
                  - verify:
                      set:
                        result: ${ .value }
            """.trimIndent(),
            validate = expectOutputMatching("result=42") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 42
            }
        ),

        WorkflowTestCase(
            name = "wait task inside for loop executes multiple times with distinct results",
            yaml = $$"""
                do:
                  - init:
                      set:
                        results: []
                  - loopWithWait:
                      for:
                        in: ${ [1, 2, 3] }
                      do:
                        - waitInLoop:
                            wait:
                              milliseconds: 5
                        - afterWait:
                            set:
                              value: ${ $item * 10 }
                            export:
                              as:
                                results: ${ $context.results + [.value] }
                      output:
                        as: ${ $context }
            """.trimIndent(),
            validate = expectOutputMatching("3 iterations with values [10, 20, 30]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val results = obj["results"]?.jsonArray ?: return@expectOutputMatching false
                if (results.size != 3) return@expectOutputMatching false

                val values = results.mapNotNull { it.jsonPrimitive.int }
                values == listOf(10, 20, 30)
            }
        )
    )
}
