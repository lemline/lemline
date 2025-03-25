package com.lemline.swruntime.sw.workflows

import com.lemline.swruntime.sw.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue("high"))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("high1"),  // expected
            high.rootInstance.transformedOutput  // actual
        )

        val low = getWorkflowInstance(doYaml, JsonUtils.fromValue("low"))

        // run (one shot)
        low.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("low2"),  // expected
            low.rootInstance.transformedOutput  // actual
        )

        val none = getWorkflowInstance(doYaml, JsonUtils.fromValue("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("none3"),  // expected
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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue("high"))

        // run (one shot)
        high.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("high1"),  // expected
            high.rootInstance.transformedOutput  // actual
        )

        val low = getWorkflowInstance(doYaml, JsonUtils.fromValue("low"))

        // run (one shot)
        low.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("low"),  // expected
            low.rootInstance.transformedOutput  // actual
        )

        val none = getWorkflowInstance(doYaml, JsonUtils.fromValue("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("none"),  // expected
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

        val none = getWorkflowInstance(doYaml, JsonUtils.fromValue("none"))

        // run (one shot)
        none.run()

        // Assert the output matches our expected transformed value
        assertEquals(
            JsonUtils.fromValue("none1"),  // expected
            none.rootInstance.transformedOutput  // actual
        )
    }

}
