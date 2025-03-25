package com.lemline.swruntime.sw.workflows

import com.lemline.swruntime.sw.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SetTest {

    @Test
    fun `set task sets output`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter: @{ 0 }
        """
        val high = getWorkflowInstance(doYaml, JsonUtils.`object`())

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("counter" to 0)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `set task use expression`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter: @{ . + 1 }
        """
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(1))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(mapOf("counter" to 2)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `multiple set tasks`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  set:
                    value: @{ .value + "2" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
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
    fun `multiple and nested set tasks`() = runTest {
        val str = ""
        val doYaml = """
            do:
              - first:
                  set:
                    value: @{ "1" }
              - second:
                  do:
                    - firstA:
                        set:
                          value: @{ .value + "2a" }
                    - firstB:
                        set:
                          value: @{ .value + "2b" }
              - third:
                  set:
                    value: @{ .value + "3" }
            output:
              as: @{ .value }
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
}
