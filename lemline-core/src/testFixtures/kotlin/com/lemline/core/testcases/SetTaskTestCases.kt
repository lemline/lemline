// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Test cases for SetTask execution.
 * Tests variable assignment and data manipulation.
 */
object SetTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "set task assigns simple values",
            yaml = """
                do:
                  - assign:
                      set:
                        number: 42
                        text: "hello"
                        flag: true
            """.trimIndent(),
            validate = expectOutputMatching("number=42, text=hello, flag=true") { output ->
                output == buildJsonObject {
                    put("number", 42)
                    put("text", "hello")
                    put("flag", true)
                }
            }
        ),

        WorkflowTestCase(
            name = "set task with expression",
            yaml = $$"""
                do:
                  - compute:
                      set:
                        result: ${ 10 + 20 }
            """.trimIndent(),
            validate = expectOutputMatching("result=30") { output ->
                output == buildJsonObject { put("result", 30) }
            }
        ),

        WorkflowTestCase(
            name = "set task overwrites previous values",
            yaml = """
                do:
                  - first:
                      set:
                        value: 10
                  - second:
                      set:
                        value: 20
            """.trimIndent(),
            validate = expectOutputMatching("value=20") { output ->
                output == buildJsonObject { put("value", 20) }
            }
        ),

        WorkflowTestCase(
            name = "set task creates nested objects",
            yaml = """
                do:
                  - nested:
                      set:
                        user:
                          name: Alice
                          age: 30
            """.trimIndent(),
            validate = expectOutputMatching("user.name=Alice, user.age=30") { output ->
                output == buildJsonObject {
                    putJsonObject("user") {
                        put("name", "Alice")
                        put("age", 30)
                    }
                }
            }
        ),

        WorkflowTestCase(
            name = "set task creates arrays",
            yaml = """
                do:
                  - arrays:
                      set:
                        numbers: [1, 2, 3]
                        strings: ["a", "b", "c"]
            """.trimIndent(),
            validate = expectOutputMatching("arrays created") { output ->
                output == buildJsonObject {
                    put(
                        "numbers", JsonArray(
                            listOf(
                                JsonPrimitive(1),
                                JsonPrimitive(2),
                                JsonPrimitive(3)
                            )
                        )
                    )
                    put(
                        "strings", JsonArray(
                            listOf(
                                JsonPrimitive("a"),
                                JsonPrimitive("b"),
                                JsonPrimitive("c")
                            )
                        )
                    )
                }
            }
        )
    )
}
