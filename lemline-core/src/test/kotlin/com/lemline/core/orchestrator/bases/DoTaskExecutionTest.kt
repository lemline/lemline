// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for DoTask execution with complete workflows.
 *
 * Tests the sequential execution of tasks within a do block,
 * verifying proper data flow and scope management.
 */
@ExperimentalTime
abstract class DoTaskExecutionTest : FunSpec() {

    init {
        test("do task executes tasks sequentially") {
            val yaml = $$"""
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
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["value"]?.jsonPrimitive?.int shouldBe null
            output["result"]?.jsonPrimitive?.int shouldBe 15
        }

        test("do task passes data between tasks") {
            val yaml = $$"""
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
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["counter"]?.jsonPrimitive?.int shouldBe 2
            val items = output["items"] as JsonArray
            items.size shouldBe 2
            items[0].jsonPrimitive.content shouldBe "first"
            items[1].jsonPrimitive.content shouldBe "second"
        }

        test("do task can access task metadata in expressions") {
            val yaml = $$"""
            do:
              - taskWithMetadata:
                  set:
                    taskName: ${ $task.name }
                    taskRef: ${ $task.reference }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(42)) as JsonObject

            output["taskName"]?.jsonPrimitive?.content shouldBe "taskWithMetadata"
            output["taskRef"]?.jsonPrimitive?.content shouldBe "/do/0/taskWithMetadata"
        }

        test("do task can transform input in first task") {
            val yaml = $$"""
            do:
              - processInput:
                  input:
                    from: ${ . * 10 }
                  set:
                    result: ${ $input }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(5)) as JsonObject

            output["result"]?.jsonPrimitive?.int shouldBe 50
        }

        test("do task can merge multiple objects") {
            val yaml = $$"""
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
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val user = output["user"]?.jsonObject
            user.shouldNotBeNull()
            user["name"]?.jsonPrimitive?.content shouldBe "Alice"
            user["age"]?.jsonPrimitive?.int shouldBe 30

            val metadata = output["metadata"]?.jsonObject
            metadata.shouldNotBeNull()
            metadata["timestamp"]?.jsonPrimitive?.content shouldBe "2025-01-01"
            metadata["version"]?.jsonPrimitive?.int shouldBe 1
        }

        test("do task can use conditional logic in set") {
            val yaml = $$"""
            do:
              - checkScore:
                  set:
                    score: 85
              - assignGrade:
                  set:
                    grade: ${ if .score >= 90 then "A" elif .score >= 80 then "B" else "C" end }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["grade"]?.jsonPrimitive?.content shouldBe "B"
        }

        test("nested do tasks execute correctly") {
            val yaml = $$"""
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
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["result"]?.jsonPrimitive?.int shouldBe 25
        }

        test("do task can access workflow descriptor") {
            val yaml = $$"""
            do:
              - checkWorkflow:
                  set:
                    hasWorkflowId: ${ $workflow.id != null }
                    hasWorkflowInput: ${ $workflow.input != null }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(42)) as JsonObject

            output["hasWorkflowId"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
            output["hasWorkflowInput"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
        }

        test("do task do not preserves data through multiple transformations") {
            val yaml = $$"""
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
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["base"]?.jsonPrimitive?.int shouldBe null
            output["added"]?.jsonPrimitive?.int shouldBe null
            output["final"]?.jsonPrimitive?.int shouldBe 55
        }

        test("do task with output transformation") {
            val yaml = $$"""
            do:
              - process:
                  set:
                    value: ${ 42 }
                    name: ${ "test" }
                  output:
                    as: '${ {result: .value, label: .name} }'
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["result"]?.jsonPrimitive?.int shouldBe 42
            output["label"]?.jsonPrimitive?.content shouldBe "test"
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
