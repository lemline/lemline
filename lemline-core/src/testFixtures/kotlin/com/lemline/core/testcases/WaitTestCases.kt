// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                output == buildJsonObject { put("step", 2) }
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
                output == buildJsonObject { put("completed", true) }
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
                output == buildJsonObject { put("counter", 2) }
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
                output == buildJsonObject { put("result", 42) }
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
                output == buildJsonObject {
                    put(
                        "results", JsonArray(
                            listOf(
                                JsonPrimitive(10),
                                JsonPrimitive(20),
                                JsonPrimitive(30)
                            )
                        )
                    )
                }
            }
        )
    )
}
