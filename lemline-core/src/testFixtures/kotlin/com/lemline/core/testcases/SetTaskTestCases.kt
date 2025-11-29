// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["number"]?.jsonPrimitive?.int == 42 &&
                    obj["text"]?.jsonPrimitive?.content == "hello" &&
                    obj["flag"]?.jsonPrimitive?.content == "true"
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
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 30
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
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["value"]?.jsonPrimitive?.int == 20
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
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val user = obj["user"] as? JsonObject ?: return@expectOutputMatching false
                user["name"]?.jsonPrimitive?.content == "Alice" &&
                    user["age"]?.jsonPrimitive?.int == 30
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
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("numbers") && obj.containsKey("strings")
            }
        )
    )
}
