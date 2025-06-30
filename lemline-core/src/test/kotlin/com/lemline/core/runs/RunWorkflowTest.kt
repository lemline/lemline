// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.core.getWorkflowInstance
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class RunWorkflowTest {

    val factorialWorkflowYaml = """
do:
  - checkBaseCase:
      switch:
        - base:
            when: @{ .n == 1 }
            then: returnOne

        - default:
            then: computeRecursive

  - returnOne:
      set:
        n: 1
      then: end

  - computeRecursive:
      do:
        - callFactorialNMinus1:
            run:
              workflow:
                namespace: another-one
                name: factorial
                version: '0.1.0'
                input:
                  n: @{ .n - 1 }
        - multiplyResults:
            set:
              n: @{ .n * @workflow.input.n }
            then: end
            """

    @Test
    fun `should run sub-workflow synchronously and return correct result`() = runTest {
        val instance = getWorkflowInstance(
            factorialWorkflowYaml,
            JsonObject(mapOf("n" to JsonPrimitive(5))),
            "factorial",
            "0.1.0"
        )

        val result = instance.run()

        instance.status shouldBe WorkflowStatus.COMPLETED
        // The final result of the factorial workflow is just the calculated number.
        // 5! = 120
        result shouldBe JsonObject(mapOf("n" to JsonPrimitive(120)))
    }
}
