// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for DoTask execution with complete workflows.
 *
 * Tests the sequential execution of tasks within a do block,
 * verifying proper data flow and scope management.
 */
object DoTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "do task executes tasks sequentially",
            yaml = $$"""
                do:
                  - step1:
                      set:
                        value: 10
                  - step2:
                      wait:
                        milliseconds: 10
                  - step3:
                      set:
                        result: ${ .value + 5 }
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("result should be 15, value should not be present") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["value"]?.jsonPrimitive?.int == null &&
                    obj["result"]?.jsonPrimitive?.int == 15
            }
        ),

        WorkflowTestCase(
            name = "do task passes data between tasks",
            yaml = $$"""
                do:
                  - initialize:
                      set:
                        counter: 0
                        items: []
                  - addItem1:
                      set:
                        items: ${ .items + ["first"] }
                        counter: ${ .counter + 1 }
                  - addItem2:
                      set:
                        items: ${ .items + ["second"] }
                        counter: ${ .counter + 1 }
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("counter=2, items=[first, second]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val counter = obj["counter"]?.jsonPrimitive?.int
                val items = obj["items"] as? JsonArray
                counter == 2 &&
                    items?.size == 2 &&
                    items[0].jsonPrimitive.content == "first" &&
                    items[1].jsonPrimitive.content == "second"
            }
        ),

        WorkflowTestCase(
            name = "do task can access task metadata in expressions",
            yaml = $$"""
                do:
                  - taskWithMetadata:
                      set:
                        taskName: ${ $task.name }
                        taskRef: ${ $task.reference }
            """.trimIndent(),
            input = kotlinx.serialization.json.JsonPrimitive(42),
            validate = expectOutputMatching("taskName=taskWithMetadata, taskRef=/do/taskWithMetadata") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["taskName"]?.jsonPrimitive?.content == "taskWithMetadata" &&
                    obj["taskRef"]?.jsonPrimitive?.content == "/do/taskWithMetadata"
            }
        ),

        WorkflowTestCase(
            name = "do task can transform input in first task",
            yaml = $$"""
                do:
                  - processInput:
                      input:
                        from: ${ . * 10 }
                      set:
                        result: ${ $input }
            """.trimIndent(),
            input = kotlinx.serialization.json.JsonPrimitive(5),
            validate = expectOutputMatching("result=50") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 50
            }
        ),

        WorkflowTestCase(
            name = "do task can merge multiple objects",
            yaml = $$"""
                do:
                  - createUser:
                      set:
                        name: Alice
                        age: 30
                  - addMetadata:
                      set:
                        name: ${ .name }
                        age: ${ .age }
                        timestamp: "2025-01-01"
                        version:  1
                  - combine:
                      set:
                        user: '${ {name: .name, age: .age} }'
                        metadata: '${ {timestamp: .timestamp, version: .version} }'
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("user and metadata objects") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val user = obj["user"]?.jsonObject
                val metadata = obj["metadata"]?.jsonObject
                user != null &&
                    user["name"]?.jsonPrimitive?.content == "Alice" &&
                    user["age"]?.jsonPrimitive?.int == 30 &&
                    metadata != null &&
                    metadata["timestamp"]?.jsonPrimitive?.content == "2025-01-01" &&
                    metadata["version"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "do task can use conditional logic in set",
            yaml = $$"""
                do:
                  - checkScore:
                      set:
                        score: 85
                  - assignGrade:
                      set:
                        grade: ${ if .score >= 90 then "A" elif .score >= 80 then "B" else "C" end }
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("grade=B") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["grade"]?.jsonPrimitive?.content == "B"
            }
        ),

        WorkflowTestCase(
            name = "nested do tasks execute correctly",
            yaml = $$"""
                do:
                  - outer:
                      do:
                        - inner1:
                            set:
                              value: 10
                        - inner2:
                            set:
                              doubled: ${ .value * 2 }
                  - final:
                      set:
                        result: ${ .doubled + 5 }
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("result=25") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 25
            }
        ),

        WorkflowTestCase(
            name = "do task can access workflow descriptor",
            yaml = $$"""
                do:
                  - checkWorkflow:
                      set:
                        hasWorkflowId: ${ $workflow.id != null }
                        hasWorkflowInput: ${ $workflow.input != null }
            """.trimIndent(),
            input = kotlinx.serialization.json.JsonPrimitive(42),
            validate = expectOutputMatching("hasWorkflowId=true, hasWorkflowInput=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["hasWorkflowId"]?.jsonPrimitive?.content?.toBoolean() == true &&
                    obj["hasWorkflowInput"]?.jsonPrimitive?.content?.toBoolean() == true
            }
        ),

        WorkflowTestCase(
            name = "do task does not preserve data through multiple transformations",
            yaml = $$"""
                do:
                  - step1:
                      set:
                        base: 100
                  - wait2:
                      wait:
                        milliseconds: 10
                  - step3:
                      set:
                        added: ${ .base + 10 }
                  - step4:
                      set:
                        final: ${ .added / 2 }
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("final=55, base and added should not be present") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["base"]?.jsonPrimitive?.int == null &&
                    obj["added"]?.jsonPrimitive?.int == null &&
                    obj["final"]?.jsonPrimitive?.int == 55
            }
        ),

        WorkflowTestCase(
            name = "do task with output transformation",
            yaml = $$"""
                do:
                  - process:
                      set:
                        value: ${ 42 }
                        name: ${ "test" }
                      output:
                        as: '${ {result: .value, label: .name} }'
            """.trimIndent(),
            input = JsonObject(emptyMap()),
            validate = expectOutputMatching("result=42, label=test") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 42 &&
                    obj["label"]?.jsonPrimitive?.content == "test"
            }
        )
    )
}
