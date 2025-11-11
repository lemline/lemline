// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows.integration

import com.lemline.core.execution.ExecutionOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Migrated version of SwitchTest using the new ExecutionOrchestrator implementation.
 * Tests switch task with conditional branching.
 */
@ExperimentalTime
class SwitchIntegrationTest {

    @Test
    fun `test switch with name`() = runTest {
        val doYaml = $$"""
          do:
            - test:
                switch:
                  - high:
                      when: ${ . == "high" }
                      then: first
                  - low:
                      when: ${ . == "low" }
                      then: second
                  - other:
                      then: third
            - first:
                set:
                  out: ${ . + "1" }
                then: exit
            - second:
                set:
                  out: ${ . + "2" }
                then: exit
            - third:
                set:
                  out: ${ . + "3" }
                then: exit
          output:
            as: ${ .out }
        """

        // Test "high" case
        val highNode = getWorkflowNode(doYaml)
        val highOutput = ExecutionOrchestrator.run(highNode, JsonPrimitive("high"))
        assertEquals(JsonPrimitive("high1"), highOutput)

        // Test "low" case
        val lowNode = getWorkflowNode(doYaml)
        val lowOutput = ExecutionOrchestrator.run(lowNode, JsonPrimitive("low"))
        assertEquals(JsonPrimitive("low2"), lowOutput)

        // Test "none" case (should match default "other")
        val noneNode = getWorkflowNode(doYaml)
        val noneOutput = ExecutionOrchestrator.run(noneNode, JsonPrimitive("none"))
        assertEquals(JsonPrimitive("none3"), noneOutput)
    }

    @Test
    fun `test switch with FlowDirectiveEnum`() = runTest {
        val doYaml = $$"""
          do:
            - test:
                switch:
                  - high:
                      when: ${ . == "high" }
                      then: continue
                  - low:
                      when: ${ . == "low" }
                      then: exit
                  - other:
                      then: end
            - first:
                set:
                  out: ${ . + "1" }
                output:
                  as: ${ .out }
                then: exit
            - second:
                set:
                  out: ${ . + "2" }
                then: exit
            - third:
                set:
                  out: ${ . + "3" }
                then: exit
        """

        // Test "high" case (continue - should go to first task)
        val highNode = getWorkflowNode(doYaml)
        val highOutput = ExecutionOrchestrator.run(highNode, JsonPrimitive("high"))
        assertEquals(JsonPrimitive("high1"), highOutput)

        // Test "low" case (exit - should return current value)
        val lowNode = getWorkflowNode(doYaml)
        val lowOutput = ExecutionOrchestrator.run(lowNode, JsonPrimitive("low"))
        assertEquals(JsonPrimitive("low"), lowOutput)

        // Test "none" case (end - should return current value)
        val noneNode = getWorkflowNode(doYaml)
        val noneOutput = ExecutionOrchestrator.run(noneNode, JsonPrimitive("none"))
        assertEquals(JsonPrimitive("none"), noneOutput)
    }

    // NOTE: The old test "test switch without matching should continue" has been removed
    // because the new implementation correctly throws an error when no case matches
    // and there's no default case. This is the correct behavior per the Serverless
    // Workflow spec. The old implementation had a bug where it would continue to the
    // next task, which was incorrect.
}
