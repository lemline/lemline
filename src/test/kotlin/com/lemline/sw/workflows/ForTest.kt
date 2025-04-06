package com.lemline.sw.workflows

import com.lemline.common.json.Json
import com.lemline.sw.getWorkflowInstance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ForTest {

    @Test
    fun `test for`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   in: .input 
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @item }
                 output:
                   as:  .counter 
        """
        val instance = getWorkflowInstance(doYaml, Json.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(6),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test for with while`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   in: @{ .input }
                 while: @{ @index < 2 }
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @item }
                 output:
                   as: @{ .counter }
        """
        val instance = getWorkflowInstance(doYaml, Json.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test for with named each`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   each: number
                   in: @{ .input }
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @number }
                 output:
                   as: @{ .counter }
        """
        val instance = getWorkflowInstance(doYaml, Json.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(6),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test index`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   in: @{ .input }
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @index }
                 output:
                   as: @{ .counter }
        """
        val instance = getWorkflowInstance(doYaml, Json.encodeToElement(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test with named index`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   at: var
                   in: @{ .input }
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @var }
                 output:
                   as: @{ .counter }
        """
        val instance = getWorkflowInstance(doYaml, Json.encodeToElement(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3),  // expected
            instance.rootInstance.transformedOutput  // actual
        )
    }

}
