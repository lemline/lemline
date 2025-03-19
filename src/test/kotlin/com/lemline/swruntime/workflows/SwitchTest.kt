package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SwitchTest {

    @Test
    fun `test switch named`() = runTest {

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
                output:
                  as: @{ . + "1" }
                set:
                  input: @{ . }
                then: exit
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
    fun `test switch enum`() = runTest {

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
                output:
                  as: @{ . + "1" }
                set:
                  input: @{ . }
                then: exit
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

}
