// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
            input = buildJsonObject { },
            validate = expectOutputMatching("result=15") { output ->
                output == buildJsonObject { put("result", 15) }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("counter=2, items=[first, second]") { output ->
                output == buildJsonObject {
                    put("items", buildJsonArray {
                        add(JsonPrimitive("first"))
                        add(JsonPrimitive("second"))
                    })
                    put("counter", 2)
                }
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
            input = JsonPrimitive(42),
            validate = expectOutputMatching("taskName=taskWithMetadata, taskRef=/do/0/taskWithMetadata") { output ->
                output == buildJsonObject {
                    put("taskName", "taskWithMetadata")
                    put("taskRef", "/do/0/taskWithMetadata")
                }
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
            input = JsonPrimitive(5),
            validate = expectOutputMatching("result=50") { output ->
                output == buildJsonObject { put("result", 50) }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("user and metadata objects") { output ->
                output == buildJsonObject {
                    putJsonObject("user") {
                        put("name", "Alice")
                        put("age", 30)
                    }
                    putJsonObject("metadata") {
                        put("timestamp", "2025-01-01")
                        put("version", 1)
                    }
                }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("grade=B") { output ->
                output == buildJsonObject { put("grade", "B") }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("result=25") { output ->
                output == buildJsonObject { put("result", 25) }
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
            input = JsonPrimitive(42),
            validate = expectOutputMatching("hasWorkflowId=true, hasWorkflowInput=true") { output ->
                output == buildJsonObject {
                    put("hasWorkflowId", true)
                    put("hasWorkflowInput", true)
                }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("final=55") { output ->
                output == buildJsonObject { put("final", 55) }
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
            input = buildJsonObject { },
            validate = expectOutputMatching("result=42, label=test") { output ->
                output == buildJsonObject {
                    put("result", 42)
                    put("label", "test")
                }
            }
        )
    )
}
