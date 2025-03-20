package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DataFlowTest {

    @Test
    fun `test workflow input from directive`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {in: .} }"
            do:
              - first:
                  set:
                    value: @{ .in }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test workflow output as directive`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ . }
            output:
              as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test workflow input from and output as directive`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test task input from directive`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  input:
                    from: "@{ {in: .} }"
                  set:
                    value: "@{ .in }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("value" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test task output as directive`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  set:
                    value: "@{ . }"
                  output:
                    as: "@{ {out: .value} }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test task input from & output as directive`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test multiple tasks input from & output as directive`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out2" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test all directives together`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("out" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }
}
