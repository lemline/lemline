// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IfTest {

    @Test
    fun `test without if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                set:
                  in: @{ .in + 2 }
             - c:
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 6)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test first if false`() = runTest {

        val doYaml = """
           do:
             - a:
                if: @{ .in > 0 }
                set:
                  in: @{ .in + 1 }
             - b:
                set:
                  in: @{ .in + 2 }
             - c:
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 5)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test first if true`() = runTest {

        val doYaml = """
           do:
             - a:
                if: @{ .in == 0 }
                set:
                  in: @{ .in + 1 }
             - b:
                set:
                  in: @{ .in + 2 }
             - c:
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 6)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test with subsequent if`() = runTest {

        val doYaml = """
           do:
             - a:
                if: @{ .in > 0 }
                set:
                  in: @{ .in + 1 }
             - b:
                if: @{ .in > 0 }
                set:
                  in: @{ .in + 2 }
             - c:
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 3)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test with second if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                if: @{ .in > 1 }
                set:
                  in: @{ .in + 2 }
             - c:
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 4)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test with last if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                set:
                  in: @{ .in + 2 }
             - c:
                if: @{ .in > 10 }
                set:
                  in: @{ .in + 3}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 3)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested without if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                do:
                 - b1:
                    set:
                      in: @{ .in + 2 }
                 - b2:
                    set:
                      in: @{ .in + 3 }
                 - b3:
                    set:
                      in: @{ .in + 4}
             - c:
                set:
                  in: @{ .in + 5}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 15)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested with if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                if: @{ .in > 1 }
                do:
                 - b1:
                    set:
                      in: @{ .in + 2 }
                 - b2:
                    set:
                      in: @{ .in + 3 }
                 - b3:
                    set:
                      in: @{ .in + 4}
             - c:
                set:
                  in: @{ .in + 5}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 6)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test nested with nested if`() = runTest {

        val doYaml = """
           do:
             - a:
                set:
                  in: @{ .in + 1 }
             - b:
                do:
                 - b1:
                    if: @{ .in > 1 }
                    set:
                      in: @{ .in + 2 }
                 - b2:
                    set:
                      in: @{ .in + 3 }
                 - b3:
                    set:
                      in: @{ .in + 4}
             - c:
                set:
                  in: @{ .in + 5}
        """
        val high = getWorkflowInstance(doYaml, LemlineJson.encodeToElement(mapOf("in" to 0)))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            LemlineJson.encodeToElement(mapOf("in" to 13)),  // expected
            high.rootInstance.transformedOutput  // actual
        )
    }

}
