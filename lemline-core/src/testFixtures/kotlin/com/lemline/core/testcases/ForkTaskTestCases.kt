// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectErrorContaining
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Test cases for ForkTask execution.
 * Tests parallel execution of tasks within a fork.
 */
object ForkTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "fork task executes branches in parallel (cooperative mode)",
            yaml = """
                do:
                  - parallelWork:
                      fork:
                        compete: false
                        branches:
                          - branch1:
                              set:
                                result: "A"
                          - branch2:
                              set:
                                result: "B"
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("result", "A") })
                    add(buildJsonObject { put("result", "B") })
                }
            )
        ),

        WorkflowTestCase(
            name = "the fork task returns first completed branch in compete mode",
            yaml = """
                do:
                  - raceWork:
                      fork:
                        compete: true
                        branches:
                          - slowBranch:
                              do:
                                - waiting:
                                    wait:
                                      seconds: 1
                                - first:
                                    set:
                                        winner: "first"
                          - fastBranch:
                              set:
                                winner: "second"
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("winner", "second") })
        ),

        WorkflowTestCase(
            name = "fork task passes same input to all branches",
            yaml = $$"""
                do:
                  - parallelProcess:
                      input:
                        from:
                          value: 42
                      fork:
                        branches:
                          - branch1:
                              set:
                                fromInput: ${ .value }
                          - branch2:
                              set:
                                fromInput: ${ .value }
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("fromInput", 42) })
                    add(buildJsonObject { put("fromInput", 42) })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task preserves output order in cooperative mode",
            yaml = """
                do:
                  - orderedFork:
                      fork:
                        branches:
                          - first:
                              set:
                                order: 1
                          - second:
                              set:
                                order: 2
                          - third:
                              set:
                                order: 3
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("order", 1) })
                    add(buildJsonObject { put("order", 2) })
                    add(buildJsonObject { put("order", 3) })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task supports output transformation",
            yaml = $$"""
                do:
                  - transformedFork:
                      fork:
                        branches:
                          - branch1:
                              set:
                                value: 10
                          - branch2:
                              set:
                                value: 20
                      output:
                        as:
                          total: ${ .[0].value + .[1].value }
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("total", 30) })
        ),

        WorkflowTestCase(
            name = "fork task supports nested control flow in branches",
            yaml = """
                do:
                  - nestedFork:
                      fork:
                        branches:
                          - withDo:
                              do:
                                - step1:
                                    set:
                                      a: 1
                                - step2:
                                    set:
                                      b: 2
                          - withSet:
                              set:
                                c: 3
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("b", 2) })
                    add(buildJsonObject { put("c", 3) })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task respects if condition - executes",
            yaml = $$"""
                do:
                  - conditionalFork:
                      if: ${ .shouldRun }
                      fork:
                        branches:
                          - branch1:
                              set:
                                executed: true
            """.trimIndent(),
            input = JsonObject(mapOf("shouldRun" to JsonPrimitive(true))),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("executed", true) })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task respects if condition - skipped",
            yaml = $$"""
                do:
                  - conditionalFork:
                      if: ${ .shouldRun }
                      fork:
                        branches:
                          - branch1:
                              set:
                                executed: true
            """.trimIndent(),
            input = JsonObject(mapOf("shouldRun" to JsonPrimitive(false))),
            validate = expectOutput(buildJsonObject { put("shouldRun", false) })
        ),

        WorkflowTestCase(
            name = "fork task with single branch",
            yaml = """
                do:
                  - singleBranchFork:
                      fork:
                        branches:
                          - onlyBranch:
                              set:
                                result: "solo"
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("result", "solo") })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task supports export context",
            yaml = $$"""
                do:
                  - forkWithExport:
                      fork:
                        branches:
                          - branch1:
                              set:
                                value: 10
                          - branch2:
                              set:
                                value: 20
                      export:
                        as:
                          forkResults: .
                  - useExport:
                      set:
                        total: ${ $context.forkResults[0].value + $context.forkResults[1].value }
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("total", 30) })
        ),

        WorkflowTestCase(
            name = "fork task with empty branches defaults to cooperative mode",
            yaml = """
                do:
                  - defaultModeFork:
                      fork:
                        branches:
                          - branch1:
                              set:
                                mode: "cooperative1"
                          - branch2:
                              set:
                                mode: "cooperative2"
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("mode", "cooperative1") })
                    add(buildJsonObject { put("mode", "cooperative2") })
                }
            )
        ),

        WorkflowTestCase(
            name = "fork task in compete mode ignores failures and waits for first success",
            yaml = """
                do:
                  - racingFork:
                      fork:
                        compete: true
                        branches:
                          - slowSuccess:
                              do:
                                - waiting:
                                    wait:
                                      seconds: 1
                                - succeeding:
                                    set:
                                        winner: "success"
                          - fastFailing:
                              raise:
                                  error:
                                    type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                                    status: 500
            """.trimIndent(),
            validate = expectOutput(buildJsonObject { put("winner", "success") })
        ),

        WorkflowTestCase(
            name = "fork task in compete mode fails when all branches fail",
            yaml = """
                do:
                  - failingFork:
                      fork:
                        compete: true
                        branches:
                          - branch1:
                              do:
                                - waiting:
                                    wait:
                                      seconds: 1
                                - failing:
                                    raise:
                                        error:
                                          type: https://serverlessworkflow.io/spec/1.0.0/errors/Error1
                                          status: 400
                          - branch2:
                              raise:
                                error:
                                  type: https://serverlessworkflow.io/spec/1.0.0/errors/Error2
                                  status: 500
            """.trimIndent(),
            validate = expectErrorContaining("Error1")
        ),

        WorkflowTestCase(
            name = "fork task in cooperative mode fails immediately on first branch failure",
            yaml = """
                do:
                  - cooperativeFork:
                      fork:
                        compete: false
                        branches:
                          - fastSuccess:
                              set:
                                result: "fast"
                          - slowFailure:
                              do:
                                - waiting:
                                    wait:
                                      seconds: 1
                                - failing:
                                    raise:
                                        error:
                                          type: https://serverlessworkflow.io/spec/1.0.0/errors/first
                                          status: 400
            """.trimIndent(),
            validate = expectErrorContaining("first")
        ),

        WorkflowTestCase(
            name = "fork task errors can be caught by parent try/catch",
            yaml = $$"""
                do:
                  - tryCatchFork:
                      try:
                        - failingFork:
                              fork:
                                compete: false
                                branches:
                                  - branch1:
                                      raise:
                                        error:
                                          type: https://serverlessworkflow.io/spec/1.0.0/errors/catchableError
                                          status: 400
                                  - branch2:
                                      set:
                                        value: "ok"
                      catch:
                          as: failure
                          do:
                            - handleError:
                                set:
                                  caught: true
                                  errorType: ${ $failure.type }
            """.trimIndent(),
            validate = expectOutputMatching("caught=true, errorType contains catchableError") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["caught"]?.jsonPrimitive?.content == "true" &&
                    obj["errorType"]?.jsonPrimitive?.content?.contains("catchableError") == true
            }
        ),

        WorkflowTestCase(
            name = "fork task inside for loop executes multiple times with distinct results",
            yaml = $$"""
                do:
                  - init:
                      set:
                        results: []
                  - loopWithFork:
                      for:
                        in: ${ [1, 2, 3] }
                      do:
                        - parallelInLoop:
                            fork:
                              branches:
                                - branchA:
                                    set:
                                      value: ${ $item * 10 }
                                - branchB:
                                    set:
                                      value: ${ $item * 100 }
                            export:
                              as:
                                results: ${ $context.results + [.] }
                      output:
                        as: ${ $context }
            """.trimIndent(),
            validate = expectOutputMatching("3 iterations with values [10,100], [20,200], [30,300]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val results = obj["results"] as? JsonArray ?: return@expectOutputMatching false
                if (results.size != 3) return@expectOutputMatching false

                fun JsonArray.containsValues(vararg expected: Int): Boolean {
                    val actual = this.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.int }.toSet()
                    return actual == expected.toSet()
                }

                (results[0] as? JsonArray)?.containsValues(10, 100) == true &&
                    (results[1] as? JsonArray)?.containsValues(20, 200) == true &&
                    (results[2] as? JsonArray)?.containsValues(30, 300) == true
            }
        ),

        WorkflowTestCase(
            name = "fork task with duplicate branch names executes all branches",
            yaml = """
                do:
                  - forkWithDuplicateNames:
                      fork:
                        branches:
                          - sameName:
                              set:
                                value: 1
                          - sameName:
                              set:
                                value: 2
                          - sameName:
                              set:
                                value: 3
            """.trimIndent(),
            validate = expectOutput(
                buildJsonArray {
                    add(buildJsonObject { put("value", 1) })
                    add(buildJsonObject { put("value", 2) })
                    add(buildJsonObject { put("value", 3) })
                }
            )
        )
    )
}
