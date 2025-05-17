// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

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
        val instance = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(6), // expected
            instance.rootInstance.transformedOutput, // actual
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
        val instance = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3), // expected
            instance.rootInstance.transformedOutput, // actual
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
        val instance = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(6), // expected
            instance.rootInstance.transformedOutput, // actual
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
        val instance = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3), // expected
            instance.rootInstance.transformedOutput, // actual
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
        val instance = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        instance.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive(3), // expected
            instance.rootInstance.transformedOutput, // actual
        )
    }
}
