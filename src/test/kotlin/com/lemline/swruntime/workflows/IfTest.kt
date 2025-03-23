package com.lemline.swruntime.workflows

import com.lemline.swruntime.utils.getWorkflowInstance
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class IfTest {

    @Test
    fun `test for`() = runTest {

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
        val high = getWorkflowInstance(doYaml, JsonUtils.fromValue(mapOf("in" to 0)))

        // run (one shot)
//        high.run()
//
//        // Assert the output matches our expected transformed value
//        assertEquals(
//            JsonUtils.fromValue(mapOf("in" to 6)),  // expected
//            high.rootInstance.transformedOutput  // actual
//        )
    }


}
