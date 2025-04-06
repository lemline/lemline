package com.lemline.sw.workflows

import com.lemline.common.json.Json
import com.lemline.sw.getWorkflowInstance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SetTest {

    @Test
    fun `set task sets output`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter: 0 
        """
        val instance = getWorkflowInstance(doYaml, JsonObject(mapOf()))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            Json.encodeToElement(mapOf("counter" to 0)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `set task does not use expression by default`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter:  .
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(1))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            Json.encodeToElement(mapOf("counter" to ".")),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `set task can use expression`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter: @{ . + 1 }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(1))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            Json.encodeToElement(mapOf("counter" to 2)),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `set value can be nested`() = runTest {

        val doYaml = """
           do:
             - first:
                set:
                  counter:  
                    a: 0
                    b: @{ . }
        """
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(1))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            Json.encodeToElement(mapOf("counter" to mapOf("a" to 0, "b" to 1))),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `multiple set tasks`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("123"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `multiple and nested set tasks`() = runTest {
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
        val instance = getWorkflowInstance(doYaml, JsonPrimitive(""))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("12a2b3"),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }
}
