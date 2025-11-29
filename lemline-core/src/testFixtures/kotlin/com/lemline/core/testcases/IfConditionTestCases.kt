// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for if condition execution.
 * Tests conditional task execution.
 */
object IfConditionTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "if condition executes when true",
            yaml = $$"""
                do:
                  - conditional:
                      if: ${ true }
                      set:
                        executed: true
            """.trimIndent(),
            validate = expectOutputMatching("executed=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["executed"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "if condition skips when false",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        initial: 1
                  - conditional:
                      if: ${ false }
                      set:
                        executed: true
            """.trimIndent(),
            validate = expectOutputMatching("executed not set, initial=1") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                !obj.containsKey("executed") && obj["initial"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "if condition with expression",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        value: 10
                  - conditional:
                      if: ${ .value > 5 }
                      set:
                        result: "greater"
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("result=greater") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "greater"
            }
        ),

        WorkflowTestCase(
            name = "if condition with input data",
            yaml = $$"""
                do:
                  - conditional:
                      if: ${ .flag == true }
                      set:
                        result: "flag was true"
            """.trimIndent(),
            input = JsonObject(mapOf("flag" to JsonPrimitive(true))),
            validate = expectOutputMatching("result=flag was true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "flag was true"
            }
        ),

        WorkflowTestCase(
            name = "multiple if conditions in sequence",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        value: 15
                  - check1:
                      if: ${ .value > 10 }
                      set:
                        pass1: true
                        value: ${ .value }
                  - check2:
                      if: ${ .value > 20 }
                      set:
                        pass2: true
            """.trimIndent(),
            validate = expectOutputMatching("pass1=true, pass2 not set") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["pass1"]?.jsonPrimitive?.content == "true" &&
                    !obj.containsKey("pass2")
            }
        ),

        WorkflowTestCase(
            name = "if-else pattern using multiple conditions",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        score: 75
                  - gradeA:
                      if: ${ .score >= 90 }
                      set:
                        grade: "A"
                      then: done
                  - gradeB:
                      if: ${ .score >= 80 }
                      set:
                        grade: "B"
                      then: done
                  - gradeC:
                      if: ${ .score >= 70 }
                      set:
                        grade: "C"
                      then: done
                  - gradeF:
                      set:
                        grade: "F"
                  - done:
                      set:
                        grade: ${ .grade }
            """.trimIndent(),
            validate = expectOutputMatching("grade=C") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["grade"]?.jsonPrimitive?.content == "C"
            }
        )
    )
}
