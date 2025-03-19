package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ThenTest {

    @Test
    fun `test without then`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    input: @{ . }
              - second:
                  output:
                    as: @{ . + "2" }
                  set:
                    value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("123"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested without then`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  do:
                    - a:
                        output:
                          as: @{ . + "2a" }
                        set:
                          value: @{ . }
                    - b:
                        output:
                          as: @{ . + "2b" }
                        set:
                          value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12a2b3"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test continue`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
                  then: continue
              - second:
                  output:
                    as: @{ . + "2" }
                  set:
                    value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("123"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested continue`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  do:
                    - a:
                        output:
                          as: @{ . + "2a" }
                        set:
                          value: @{ . }
                        then: continue
                    - b:
                        output:
                          as: @{ . + "2b" }
                        set:
                          value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12a2b3"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test end`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  output:
                    as: @{ . + "2" }
                  set:
                    value: @{ . }
                  then: end
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested end`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  do:
                    - a:
                        output:
                          as: @{ . + "2a" }
                        set:
                          value: @{ . }
                        then: end
                    - b:
                        output:
                          as: @{ . + "2b" }
                        set:
                          value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12a"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test exit`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  output:
                    as: @{ . + "2" }
                  set:
                    value: @{ . }
                  then: exit
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested exit`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  do:
                    - a:
                        output:
                          as: @{ . + "2a" }
                        set:
                          value: @{ . }
                        then: exit
                    - b:
                        output:
                          as: @{ . + "2b" }
                        set:
                          value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12a3"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test named then`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
                  then: third
              - second:
                  output:
                    as: @{ . + "2" }
                  set:
                    value: @{ . }
                  then: exit
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("13"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested named then`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  output:
                    as: @{ . + "1" }
                  set:
                    value: @{ . }
              - second:
                  do:
                    - a:
                        output:
                          as: @{ . + "2a" }
                        set:
                          value: @{ . }
                        then: c
                    - b:
                        output:
                          as: @{ . + "2b" }
                        set:
                          value: @{ . }
                    - c:
                        output:
                          as: @{ . + "2c" }
                        set:
                          value: @{ . }
              - third:
                  output:
                    as: @{ . + "3" }
                  set:
                    value: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonUtils.fromValue(str))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("12a2c3"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }
}
