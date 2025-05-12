// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class FlowDirectiveTest {

    @Test
    fun `test continue`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
                  then: continue
              - second:
                  set:
                    value: @{ .value + "2" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("123"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test nested continue`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: @{ .value + "2a" }
                        then: continue
                    - secondB:
                        set:
                          value: @{ .value + "2b" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a2b3"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test end`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  set:
                    value: @{ .value + "2" }
                  then: end
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test end with output as`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  set:
                    value: @{ .value + "2" }
                  output:
                    as: @{ .value }
                  then: end
              - third:
                  set:
                    value: @{ .value + "3" }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test nested end`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: @{ .value + "2a" }
                        then: end
                    - secondB:
                        set:
                          value: @{ .value + "2b" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test exit`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  set:
                    value: @{ .value + "2" }
                  then: exit
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test exit with output as`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  set:
                    value: @{ .value + "2" }
                  output:
                    as: @{ .value }
                  then: exit
              - third:
                  set:
                    value: @{ .value + "3" }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test nested exit`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: @{ .value + "2a" }
                        then: exit
                    - secondB:
                        set:
                          value: @{ .value + "2b" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a3"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test named then`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
                  then: third
              - second:
                  set:
                    value: @{ .value + "2" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("13"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }

    @Test
    fun `test nested named then`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  do:
                    - secondA:
                        set:
                          value: @{ .value + "2a" }
                        then: secondC
                    - secondB:
                        set:
                          value: @{ .value + "2b" }
                    - secondC:
                        set:
                          value: @{ .value + "2c" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a2c3"), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }
}
