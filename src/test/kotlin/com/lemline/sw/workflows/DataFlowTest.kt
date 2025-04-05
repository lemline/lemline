package com.lemline.sw.workflows

import com.lemline.sw.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DataFlowTest {

    @Test
    fun `check workflow input-from (expr)`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {in: .} }"
            do:
              - first:
                  set:
                    value: @{ .in }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow input-from (json)`() = runTest {
        val doYaml = """
            input:
              from: "{in: .}"
            do:
              - first:
                  set:
                    value: @{ .in }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive("foo"))

        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to "foo")),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow input-from (yaml)`() = runTest {
        val doYaml = """
            input:
              from: 
                in: .
            do:
              - first:
                  set:
                    value: @{ .in }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive("foo"))

        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to "foo")),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow output-as (expr)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ . }
            output:
              as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow output-as (json)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ . }
            output:
              as: "{out: .value}"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow output-as (yaml)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ . }
            output:
              as: 
                out: .value
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check workflow input-from and output-as`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {in: .} }"
            do:
              - first:
                  set:
                    value: @{ .in }
            output:
              as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task input-from (expr)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: "@{ {in: .} }"
                  set:
                    value: "@{ .in }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task input-from (json)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: "{in: .}"
                  set:
                    value: "@{ .in }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task input-from (yaml)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: 
                        in: .
                  set:
                    value: "@{ .in }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task output-as (expr)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: "@{ . }"
                  output:
                    as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task output-as (json)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: "@{ . }"
                  output:
                    as: "{out: .value}"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task output-as (yaml)`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: "@{ . }"
                  output:
                    as: 
                      out: .value
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check task input-from & output-as`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: "@{ {in: .} }"
                  set:
                    value: "@{ .in }"
                  output:
                    as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check multiple tasks input-from & output-as`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: "@{ {in1: .} }"
                  set:
                    value: "@{ .in1 }"
                  output:
                    as: "@{ {out1: .value} }"
              - second:
                  input:
                    from: "@{ {in2: .out1} }"
                  set:
                    value: "@{ .in2 }"
                  output:
                    as: "@{ {out2: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out2" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check all together`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {in: .} }"
            do:
              - first:
                  input:
                    from: "@{ {in1: .in} }"
                  set:
                    value: "@{ .in1 }"
                  output:
                    as: "@{ {out1: .value} }"
              - second:
                  input:
                    from: "@{ {in2: .out1} }"
                  set:
                    value: "@{ .in2 }"
                  output:
                    as: "@{ {out2: .value} }"
            output:
              as: "@{ {out: .out2} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check all together (json)`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "{in: .}"
            do:
              - first:
                  input:
                    from: {in1: .in}
                  set:
                    value: @{ .in1 }
                  output:
                    as: {out1: .value}
              - second:
                  input:
                    from: {in2: .out1}
                  set:
                    value: @{ .in2 }
                  output:
                    as: {out2: .value}
            output:
              as: {out: .out2}
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `check all together (yaml)`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "{in: .}"
            do:
              - first:
                  input:
                    from: 
                      in1: .in
                  set:
                    value: @{ .in1 }
                  output:
                    as: 
                      out1: .value
              - second:
                  input:
                    from: 
                      in2: .out1
                  set:
                    value: @{ .in2 }
                  output:
                    as: 
                      out2: .value
            output:
              as: 
                out: .out2
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

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
            JsonUtils.fromValue(mapOf("number" to 42)),
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
            JsonUtils.fromValue(mapOf("value" to "baz")),
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
            JsonUtils.fromValue(mapOf("value" to "nested")),
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
            JsonUtils.fromValue(mapOf("out" to "test_value")),
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
            JsonUtils.fromValue(mapOf("value" to "above")),
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
            JsonUtils.fromValue(mapOf("result" to 20)),
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
            JsonUtils.fromValue(mapOf("message" to "Hello, World")),
            instance.rootInstance.transformedOutput
        )
    }
}
