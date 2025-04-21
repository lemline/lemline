// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import kotlinx.coroutines.test.runTest
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
            LemlineJson.encodeToElement(mapOf("value" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("value" to "foo")), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("value" to "foo")), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("value" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("value" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("value" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out2" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
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
            LemlineJson.encodeToElement(mapOf("out" to str)), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }
}
