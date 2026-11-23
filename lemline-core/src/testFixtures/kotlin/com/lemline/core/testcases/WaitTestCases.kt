// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.fasterxml.jackson.databind.node.ArrayNode
import com.lemline.common.json.*
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput

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
            validate = expectOutput(buildJsonObject { put("step", 2) })
        ),

        WorkflowTestCase(
            name = "output.as reads the task inputs once an asynchronous task has completed",
            input = buildJsonObject { put("order", buildJsonObject { put("id", "ORD-1") }) },
            yaml = $$"""
                do:
                  - waiting:
                      input:
                        from: ${ .order }
                      wait:
                        milliseconds: 10
                      output:
                        as: '${ { transformed: $input.id, raw: $task.input.order.id } }'
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("transformed", "ORD-1"); put("raw", "ORD-1") })
        ),

        WorkflowTestCase(
            name = "export.as reads the task inputs once an asynchronous task has completed",
            input = buildJsonObject { put("buildingId", "building-42") },
            yaml = $$"""
                do:
                  - waiting:
                      wait:
                        milliseconds: 10
                      export:
                        as: '${ { fromInput: $input.buildingId, fromTask: $task.input.buildingId } }'
                  - report:
                      set:
                        fromInput: ${ $context.fromInput }
                        fromTask: ${ $context.fromTask }
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("fromInput", "building-42"); put("fromTask", "building-42") })
        ),

        WorkflowTestCase(
            name = "output.as reads the task inputs of a task that ran other tasks",
            input = buildJsonObject { put("orderId", "ORD-1") },
            yaml = $$"""
                do:
                  - process:
                      do:
                        - pause:
                            wait:
                              milliseconds: 10
                        - compute:
                            set:
                              total: 42
                      output:
                        as: '${ $input + . }'
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("orderId", "ORD-1"); put("total", 42) })
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
            validate = expectOutput(buildJsonObject { put("completed", true) })
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
            validate = expectOutput(buildJsonObject { put("counter", 2) })
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
            validate = expectOutput(buildJsonObject { put("result", 42) })
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
            validate = expectOutput(
                buildJsonObject {
                    put(
                        "results", ArrayNode(
                            listOf(
                                jsonValue(10),
                                jsonValue(20),
                                jsonValue(30)
                            )
                        )
                    )
                }
            )
        )
    )
}
