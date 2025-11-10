// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for SetTask execution using ExecutionOrchestrator.
 *
 * Tests the evaluation of set expressions and data manipulation,
 * verifying proper scope access and data transformations.
 */
@ExperimentalTime
class SetTaskExecutionTest {

    @Test
    fun `set task can create simple values`() = runTest {
        val yaml = $$"""
            do:
              - createValues:
                  set:
                    name: ${ "Alice" }
                    age: 30
                    active: ${ true }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("Alice", output["name"]?.jsonPrimitive?.content)
        assertEquals(30, output["age"]?.jsonPrimitive?.int)
        assertEquals(true, output["active"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `set task can access input data`() = runTest {
        val yaml = $$"""
            do:
              - processInput:
                  set:
                    doubled: ${ @input * 2 }
                    original: ${ @input }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(21)) as JsonObject

        assertEquals(42, output["doubled"]?.jsonPrimitive?.int)
        assertEquals(21, output["original"]?.jsonPrimitive?.int)
    }

    @Test
    fun `set task can access previous task results`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  set:
                    value: 10
              - step2:
                  set:
                    result: ${ .value + 5 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(15, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `set task can merge objects`() = runTest {
        val yaml = $$"""
            do:
              - mergeData:
                  set:
                    user: '${ {name: "Bob", age: 25} }'
                    metadata: '${ {created: "2025-01-01", version: 1} }'
                    combined: '${ {name: "Bob", age: 25} + {created: "2025-01-01", version: 1} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val combined = output["combined"]?.jsonObject
        assertEquals("Bob", combined?.get("name")?.jsonPrimitive?.content)
        assertEquals(25, combined?.get("age")?.jsonPrimitive?.int)
        assertEquals("2025-01-01", combined?.get("created")?.jsonPrimitive?.content)
        assertEquals(1, combined?.get("version")?.jsonPrimitive?.int)
    }

    @Test
    fun `set task can transform arrays`() = runTest {
        val yaml = $$"""
            do:
              - transformArray:
                  set:
                    numbers: [1, 2, 3, 4, 5]
                    doubled: '${ [.numbers[] | . * 2] }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val doubled = output["doubled"]?.jsonArray
        assertEquals(5, doubled?.size)
        assertEquals(2, doubled?.get(0)?.jsonPrimitive?.int)
        assertEquals(4, doubled?.get(1)?.jsonPrimitive?.int)
        assertEquals(10, doubled?.get(4)?.jsonPrimitive?.int)
    }

    @Test
    fun `set task can use conditional expressions`() = runTest {
        val yaml = $$"""
            do:
              - checkValue:
                  set:
                    score: 85
                    grade: ${ if .score >= 90 then "A" elif .score >= 80 then "B" elif .score >= 70 then "C" else "F" end }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("B", output["grade"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set task can construct complex nested objects`() = runTest {
        val yaml = $$"""
            do:
              - buildComplex:
                  set:
                    person: '${ {name: "Alice", contact: {email: "alice@example.com", phone: "555-1234"}, age: 30} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val person = output["person"]?.jsonObject
        assertEquals("Alice", person?.get("name")?.jsonPrimitive?.content)
        assertEquals(30, person?.get("age")?.jsonPrimitive?.int)
        val contact = person?.get("contact")?.jsonObject
        assertEquals("alice@example.com", contact?.get("email")?.jsonPrimitive?.content)
        assertEquals("555-1234", contact?.get("phone")?.jsonPrimitive?.content)
    }

    @Test
    fun `set task can access task descriptor`() = runTest {
        val yaml = $$"""
            do:
              - myTask:
                  set:
                    taskName: ${ @task.name }
                    taskRef: ${ @task.reference }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("myTask", output["taskName"]?.jsonPrimitive?.content)
        assertEquals("/do/0/myTask", output["taskRef"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set task can use string concatenation`() = runTest {
        val yaml = $$"""
            do:
              - concatenate:
                  set:
                    firstName: ${ "Alice" }
                    lastName: ${ "Smith" }
                    fullName: ${ .firstName + " " + .lastName }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("Alice Smith", output["fullName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set task with input and output transformations`() = runTest {
        val yaml = $$"""
            do:
              - transform:
                  input:
                    from: ${ . * 10 }
                  set:
                    value: ${ @input }
                    doubled: ${ @input * 2 }
                  output:
                    as: '${ {result: .doubled} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(5)) as JsonObject

        assertEquals(100, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `set task can perform calculations`() = runTest {
        val yaml = $$"""
            do:
              - calculate:
                  set:
                    a: 10
                    b: 20
                    sum: ${ .a + .b }
                    product: ${ .a * .b }
                    difference: ${ .b - .a }
                    quotient: ${ .b / .a }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(30, output["sum"]?.jsonPrimitive?.int)
        assertEquals(200, output["product"]?.jsonPrimitive?.int)
        assertEquals(10, output["difference"]?.jsonPrimitive?.int)
        assertEquals(2, output["quotient"]?.jsonPrimitive?.int)
    }
}
