// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test cases for SwitchTask execution.
 * Tests conditional branching with switch/case statements.
 */
object SwitchTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "switch matches first case",
            yaml = $$"""
                do:
                  - route:
                      switch:
                        - caseA:
                            when: ${ true }
                            then: handleA
                        - caseB:
                            when: ${ false }
                            then: handleB
                  - handleA:
                      set:
                        result: "A"
                      then: end
                  - handleB:
                      set:
                        result: "B"
            """.trimIndent(),
            validate = expectOutputMatching("result=A") { output ->
                output == buildJsonObject {
                    put("result", "A")
                }
            }
        ),

        WorkflowTestCase(
            name = "switch matches second case",
            yaml = $$"""
                do:
                  - route:
                      switch:
                        - caseA:
                            when: ${ false }
                            then: handleA
                        - caseB:
                            when: ${ true }
                            then: handleB
                  - handleA:
                      set:
                        result: "A"
                      then: end
                  - handleB:
                      set:
                        result: "B"
            """.trimIndent(),
            validate = expectOutputMatching("result=B") { output ->
                output == buildJsonObject {
                    put("result", "B")
                }
            }
        ),

        WorkflowTestCase(
            name = "switch with expression conditions",
            yaml = $$"""
                do:
                  - setup:
                      set:
                        value: 50
                  - route:
                      switch:
                        - high:
                            when: ${ .value > 75 }
                            then: setHigh
                        - medium:
                            when: ${ .value > 25 }
                            then: setMedium
                        - low:
                            then: setLow
                  - setHigh:
                      set:
                        level: "high"
                      then: end
                  - setMedium:
                      set:
                        level: "medium"
                      then: end
                  - setLow:
                      set:
                        level: "low"
            """.trimIndent(),
            validate = expectOutputMatching("level=medium") { output ->
                output == buildJsonObject {
                    put("level", "medium")
                }
            }
        ),

        WorkflowTestCase(
            name = "switch with default case",
            yaml = $$"""
                do:
                  - route:
                      switch:
                        - caseA:
                            when: ${ false }
                            then: handleA
                        - default:
                            then: handleDefault
                  - handleA:
                      set:
                        result: "A"
                      then: end
                  - handleDefault:
                      set:
                        result: "default"
            """.trimIndent(),
            validate = expectOutputMatching("result=default") { output ->
                output == buildJsonObject {
                    put("result", "default")
                }
            }
        ),

        WorkflowTestCase(
            name = "switch based on input value",
            yaml = $$"""
                do:
                  - route:
                      switch:
                        - typeA:
                            when: ${ .type == "A" }
                            then: processA
                        - typeB:
                            when: ${ .type == "B" }
                            then: processB
                        - other:
                            then: processOther
                  - processA:
                      set:
                        processed: "type-A"
                      then: end
                  - processB:
                      set:
                        processed: "type-B"
                      then: end
                  - processOther:
                      set:
                        processed: "other"
            """.trimIndent(),
            input = buildJsonObject { put("type", "B") },
            validate = expectOutputMatching("processed=type-B") { output ->
                output == buildJsonObject {
                    put("processed", "type-B")
                }
            }
        )
    )
}
