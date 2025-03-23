package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ForTest {

    @Test
    fun `test for`() = runTest {

        val doYaml = """
           do:
             - sumAll:
                 for:
                   in: @{ .input }
                 do:
                   - accumulate:
                       set:
                         counter: @{ .counter + @item }
                 output:
                   as: @{ .counter }
        """
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(6),  // expected
            high.rootInstance.transformedOutput  // actual
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(3),  // expected
            high.rootInstance.transformedOutput  // actual
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("input" to listOf(1, 2, 3))))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(6),  // expected
            high.rootInstance.transformedOutput  // actual
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(3),  // expected
            high.rootInstance.transformedOutput  // actual
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("input" to listOf(4, 5, 6))))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue(3),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

}
