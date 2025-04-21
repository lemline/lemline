// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SwitchTest {

    @Test
    fun `test switch with name`() = runTest {

        val doYaml = """
          do:
            - test:
                switch:
                  - high:
                      when: @{ . == "high" }
                      then: first
                  - low:
                      when: @{ . == "low" }
                      then: second
                  - other:
                      then: third
            - first:
                set:
                  out: @{ . + "1" }
                then: exit
            - second:
                set:
                  out: @{ . + "2" }
                then: exit
            - third:
                set:
                  out: @{ . + "3" }
                then: exit
          output:
            as: @{ .out }
        """
        val high = getWorkflowInstance(doYaml, JsonPrimitive("high"))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("high1"),  // expected
            high.rootInstance.transformedOutput  // actual
        )

        val low = getWorkflowInstance(doYaml, JsonPrimitive("low"))

        // run (one shot)
        low.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("low2"),  // expected
            low.rootInstance.transformedOutput  // actual
        )

        val none = getWorkflowInstance(doYaml, JsonPrimitive("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("none3"),  // expected
            none.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test switch with FlowDirectiveEnum`() = runTest {

        val doYaml = """
          do:
            - test:
                switch:
                  - high:
                      when: @{ . == "high" }
                      then: continue
                  - low:
                      when: @{ . == "low" }
                      then: exit
                  - other:
                      then: end
            - first:
                set:
                  out: @{ . + "1" }
                output:
                  as: @{ .out }
                then: exit
            - second:
                set:
                  out: @{ . + "2" }
                then: exit
            - third:
                set:
                  out: @{ . + "3" }
                then: exit
        """
        val high = getWorkflowInstance(doYaml, JsonPrimitive("high"))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("high1"),  // expected
            high.rootInstance.transformedOutput  // actual
        )

        val low = getWorkflowInstance(doYaml, JsonPrimitive("low"))

        // run (one shot)
        low.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("low"),  // expected
            low.rootInstance.transformedOutput  // actual
        )

        val none = getWorkflowInstance(doYaml, JsonPrimitive("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("none"),  // expected
            none.rootInstance.transformedOutput  // actual
        )
    }

    @Test
    fun `test switch without matching should continue`() = runTest {

        val doYaml = """
          do:
            - test:
                switch:
                  - high:
                      when: @{ . == "high" }
                      then: first
                  - low:
                      when: @{ . == "low" }
                      then: second
            - first:
                set:
                  out: @{ . + "1" }
                then: exit
            - second:
                set:
                  out: @{ . + "2" }
                then: exit
            - third:
                set:
                  out: @{ . + "3" }
                then: exit
          output:
            as: @{ .out }
        """

        val none = getWorkflowInstance(doYaml, JsonPrimitive("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonPrimitive("none1"),  // expected
            none.rootInstance.transformedOutput  // actual
        )
    }

}
