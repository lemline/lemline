package com.lemline.sw.workflows

import com.lemline.common.json.Json
import com.lemline.sw.getWorkflowInstance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExpressionTest {

   
    @Test
    fun `check workflow context in expressions`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as: .
              - second:
                  set:
                    number: @{ @context.foo }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("number" to 42)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test task context in expressions`() = runTest {
        val doYaml = """
            do:
              - first:
                  context:
                    foo: "bar"
                    num: 42
                  set:
                    value: @{ .context.foo }
              - second:
                  context:
                    foo: "baz"
                  set:
                    value: @{ .context.foo }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("value" to "baz")),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test nested context in expressions`() = runTest {
        val doYaml = """
            context:
              outer:
                inner:
                  value: "nested"
            do:
              - first:
                  set:
                    value: @{ .context.outer.inner.value }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("value" to "nested")),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test context with input and output transformations`() = runTest {
        val doYaml = """
            context:
              prefix: "test_"
            input:
              from: "@{ {in: .} }"
            do:
              - first:
                  set:
                    value: @{ .context.prefix + .in }
            output:
              as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive("value"))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("out" to "test_value")),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test context with conditional expressions`() = runTest {
        val doYaml = """
            context:
              threshold: 10
            do:
              - first:
                  set:
                    value: @{ .context.threshold > 5 ? "above" : "below" }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("value" to "above")),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test context with arithmetic expressions`() = runTest {
        val doYaml = """
            context:
              base: 10
              multiplier: 2
            do:
              - first:
                  set:
                    result: @{ .context.base * .context.multiplier }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("result" to 20)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `test context with string concatenation`() = runTest {
        val doYaml = """
            context:
              greeting: "Hello"
              separator: ", "
            do:
              - first:
                  set:
                    message: @{ .context.greeting + .context.separator + "World" }
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            Json.encodeToElement(mapOf("message" to "Hello, World")),
            instance.rootInstance.transformedOutput
        )
    }
}
