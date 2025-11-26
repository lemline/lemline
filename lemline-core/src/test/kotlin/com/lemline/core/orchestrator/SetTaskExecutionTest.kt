// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base integration tests for SetTask execution.
 *
 * Tests the evaluation of set expressions and data manipulation,
 * verifying proper scope access and data transformations.
 *
 * Subclasses must implement [executeWorkflow] to provide orchestrator-specific execution.
 */
@ExperimentalTime
class SetTaskExecutionTest : FunSpec() {

    init {

        test("set task overrides previous ones") {
            val yaml = $$"""
                do:
                  - first:
                      set:
                        first: ${ "John" }
                  - name:
                      set:
                        name: ${ "Alice" }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(JsonPrimitive("Alice"), output["name"])
            assertNull(output["first"])
        }

        test("set task can create simple values") {
            val yaml = $$"""
                do:
                  - createValues:
                      set:
                        name: Alice
                        age: 30
                        active: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("Alice", output["name"]?.jsonPrimitive?.content)
            assertEquals(30, output["age"]?.jsonPrimitive?.int)
            assertEquals(true, output["active"]?.jsonPrimitive?.boolean)
        }

        test("set task can access input data") {
            val yaml = $$"""
                do:
                  - processInput:
                      set:
                        doubled: ${ $input * 2 }
                        original: ${ $input }
            """
            val output = executeWorkflow(yaml, JsonPrimitive(21)) as JsonObject

            assertEquals(42, output["doubled"]?.jsonPrimitive?.int)
            assertEquals(21, output["original"]?.jsonPrimitive?.int)
        }

        test("set task can access previous task results") {
            val yaml = $$"""
                do:
                  - step1:
                      set:
                        value: 10
                  - step2:
                      set:
                        result: ${ .value + 5 }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(15, output["result"]?.jsonPrimitive?.int)
        }

        test("set task can merge objects") {
            val yaml = $$"""
                do:
                  - mergeData:
                      set:
                        user: '${ {name: "Bob", age: 25} }'
                        metadata: '${ {created: "2025-01-01", version: 1} }'
                        combined: '${ {name: "Bob", age: 25} + {created: "2025-01-01", version: 1} }'
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val combined = output["combined"]?.jsonObject
            assertEquals("Bob", combined?.get("name")?.jsonPrimitive?.content)
            assertEquals(25, combined?.get("age")?.jsonPrimitive?.int)
            assertEquals("2025-01-01", combined?.get("created")?.jsonPrimitive?.content)
            assertEquals(1, combined?.get("version")?.jsonPrimitive?.int)
        }

        test("set task can transform arrays") {
            val yaml = $$"""
                do:
                  - setArray:
                      set:
                        numbers: ${ [1, 2, 3, 4, 5] }
                  - transformArray:
                      set:
                        doubled: ${ [.numbers[] | . * 2] }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val doubled = output["doubled"]?.jsonArray
            assertEquals(5, doubled?.size)
            assertEquals(JsonPrimitive(2), doubled?.get(0))
            assertEquals(JsonPrimitive(4), doubled?.get(1))
            assertEquals(JsonPrimitive(10), doubled?.get(4))
        }

        test("set task can use conditional expressions") {
            val yaml = $$"""
                do:
                  - setValue:
                      set:
                        score: 85
                  - checkValue:
                      set:
                        grade: ${ if .score >= 90 then "A" elif .score >= 80 then "B" elif .score >= 70 then "C" else "F" end }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("B", output["grade"]?.jsonPrimitive?.content)
        }

        test("set task can construct complex nested objects") {
            val yaml = $$"""
                do:
                  - buildComplex:
                      set:
                        person: '${ {name: "Alice", contact: {email: "alice@example.com", phone: "555-1234"}, age: 30} }'
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val person = output["person"]?.jsonObject
            assertEquals("Alice", person?.get("name")?.jsonPrimitive?.content)
            assertEquals(30, person?.get("age")?.jsonPrimitive?.int)
            val contact = person?.get("contact")?.jsonObject
            assertEquals("alice@example.com", contact?.get("email")?.jsonPrimitive?.content)
            assertEquals("555-1234", contact?.get("phone")?.jsonPrimitive?.content)
        }

        test("set task can access task descriptor") {
            val yaml = $$"""
                do:
                  - myTask:
                      set:
                        taskName: ${ $task.name }
                        taskRef: ${ $task.reference }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("myTask", output["taskName"]?.jsonPrimitive?.content)
            assertEquals("/do/myTask", output["taskRef"]?.jsonPrimitive?.content)
        }

        test("set task can use string concatenation") {
            val yaml = $$"""
                do:
                  - setStrings:
                      set:
                        firstName: ${ "Alice" }
                        lastName:  ${ "Smith" }
                  - concatenate:
                      set:
                        fullName:  ${ .firstName + " " + .lastName }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("Alice Smith", output["fullName"]?.jsonPrimitive?.content)
        }

        test("set task with input and output transformations") {
            val yaml = $$"""
                do:
                  - transform:
                      input:
                        from: ${ . * 10 }
                      set:
                        value: ${ $input }
                        doubled: ${ $input * 2 }
                      output:
                        as: '${ {result: .doubled} }'
            """
            val output = executeWorkflow(yaml, JsonPrimitive(5)) as JsonObject

            assertEquals(100, output["result"]?.jsonPrimitive?.int)
        }

        test("set task can perform calculations") {
            val yaml = $$"""
                do:
                  - setValues:
                      set:
                        a: 10
                        b: 20
                  - calculate:
                      set:
                        sum: ${ .a + .b }
                        product: ${ .a * .b }
                        difference: ${ .b - .a }
                        quotient: ${ .b / .a }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(30, output["sum"]?.jsonPrimitive?.int)
            assertEquals(200, output["product"]?.jsonPrimitive?.int)
            assertEquals(10, output["difference"]?.jsonPrimitive?.int)
            assertEquals(2, output["quotient"]?.jsonPrimitive?.int)
        }
    }
}
