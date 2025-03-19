package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FromAsTest {

    @Test
    fun `test workflow input from directive`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {data: .} }"
            do:
              - first:
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("data" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test workflow output as directive`() = runTest {
        val str = "foo"
        val doYaml = """
            output:
              as: "@{ {result: .} }"
            do:
              - first:
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("result" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test workflow input from and output as directive`() = runTest {
        val str = "foo"
        val doYaml = """
            input:
              from: "@{ {data: .} }"
            output:
              as: "@{ {result: .data} }"
            do:
              - first:
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("result" to str)),  // expected
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
                    from: "@{ {transformed: .} }"
                  set:
                    value: "@{ . }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("transformed" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test task output as directive`() = runTest {
        val str = "foo"
        val doYaml = """
            do:
              - first:
                  output:
                    as: "@{ {transformed: .} }"
                  set:
                    value: "@{ . }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("transformed" to str)),  // expected
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
                  output:
                    as: "@{ {out: .in} }"
                  set:
                    value: "@{ . }"
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
                  output:
                    as: "@{ {out1: .in1} }"
                  set:
                    value: "@{ . }"
              - second:
                  input:
                    from: "@{ {in2: .out1} }"
                  output:
                    as: "@{ {out2: .in2} }"
                  set:
                    value: "@{ . }"
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
              from: "@{ {win: .} }"
            output:
              as: "@{ {wout: .out2} }"
            do:
              - first:
                  input:
                    from: "@{ {in1: .win} }"
                  output:
                    as: "@{ {out1: .in1} }"
                  set:
                    value: "@{ . }"
              - second:
                  input:
                    from: "@{ {in2: .out1} }"
                  output:
                    as: "@{ {out2: .in2} }"
                  set:
                    value: "@{ . }"
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("wout" to str)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }
}
