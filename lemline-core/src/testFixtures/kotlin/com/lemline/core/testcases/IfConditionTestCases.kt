// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
            validate = expectOutput(buildJsonObject { put("executed", true) })
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
            validate = expectOutput(buildJsonObject { put("initial", 1) })
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
            input = buildJsonObject { },
            validate = expectOutput(buildJsonObject { put("result", "greater") })
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
            input = buildJsonObject { put("flag", true) },
            validate = expectOutput(buildJsonObject { put("result", "flag was true") })
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
            validate = expectOutput(
                buildJsonObject {
                    put("pass1", true)
                    put("value", 15)
                }
            )
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
            validate = expectOutput(buildJsonObject { put("grade", "C") })
        )
    )
}
