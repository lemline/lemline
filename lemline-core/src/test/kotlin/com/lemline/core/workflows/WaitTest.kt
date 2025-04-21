// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import com.lemline.core.nodes.activities.WaitInstance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.serverlessworkflow.impl.WorkflowStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WaitTest {

    @Test
    fun `check wait for duration`() = runTest {
        val workflowYaml = """
            do:
              - wait_step:
                  wait:
                    days: 1
                    hours: 2
                    minutes: 30
                    seconds: 15
        """
        val instance = getWorkflowInstance(workflowYaml, JsonNull)

        // Run the workflow (stops at the wait)
        instance.run()

        // Assert the current node is the wait
        instance.status shouldBe WorkflowStatus.WAITING
        instance.current.shouldBeInstanceOf<WaitInstance>()
        (instance.current as WaitInstance).delay shouldBe 1.days + 2.hours + 30.minutes + 15.seconds
        println("Continuing...")
        // Re-Run the workflow (starting from the wait)
        instance.run()

        // Assert that the elapsed time is at least the wait duration
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check wait for duration (ISO 8601)`() = runTest {
        val workflowYaml = """
            do:
              - wait_step:
                  wait: P1DT2H30M15S
        """
        val instance = getWorkflowInstance(workflowYaml, JsonNull)

        // Run the workflow (stops at the wait)
        instance.run()

        // Assert the current node is the wait
        instance.status shouldBe WorkflowStatus.WAITING
        instance.current.shouldBeInstanceOf<WaitInstance>()
        (instance.current as WaitInstance).delay shouldBe 1.days + 2.hours + 30.minutes + 15.seconds
        println("Continuing...")

        // Re-Run the workflow (starting from the wait)
        instance.run()

        // Assert that the elapsed time is at least the wait duration
        instance.status shouldBe WorkflowStatus.COMPLETED
    }
}
