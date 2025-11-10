// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.common.json.LemlineJson
import com.lemline.core.getWorkflowProcessor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for scope implementation verifying that expressions
 * have proper access to:
 * - $task.* - task descriptor (name, reference, startedAt, input, output)
 * - $input - current input data
 * - $output - current output data
 * - Parent scope variables (loop variables, exported context, etc.)
 */
@ExperimentalTime
class ScopeTest {

    // ========================================
    // Task Descriptor Access Tests
    // ========================================

    @Test
    fun `expressions can access task name in set task`() = runTest {
        val doYaml = """
            do:
              - myTaskName:
                  set:
                    taskName: @{ @task.name }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive("myTaskName"), output["taskName"])
    }

    @Test
    fun `expressions can access task reference in set task`() = runTest {
        val doYaml = """
            do:
              - myTask:
                  set:
                    taskRef: @{ @task.reference }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive("/do/0/myTask"), output["taskRef"])
    }

    @Test
    fun `expressions can access task input in set task`() = runTest {
        val doYaml = """
            do:
              - myTask:
                  set:
                    originalInput: @{ @task.input }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(42))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(42), output["originalInput"])
    }

    @Test
    fun `expressions can access task startedAt in set task`() = runTest {
        val doYaml = """
            do:
              - myTask:
                  set:
                    started: @{ @task.startedAt != null }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertTrue((output["started"] as JsonPrimitive).boolean)
    }


    // ========================================
    // $input and $output Access Tests
    // ========================================

    @Test
    fun `expressions can access $input in set task`() = runTest {
        val doYaml = """
            do:
              - myTask:
                  set:
                    inputCopy: @{ @input }
                    doubled: @{ @input * 2 }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(21))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(21), output["inputCopy"])
        assertEquals(JsonPrimitive(42), output["doubled"])
    }

    @Test
    fun `expressions can access transformed input via $input`() = runTest {
        val doYaml = """
            do:
              - myTask:
                  input:
                    from: @{ . * 10 }
                  set:
                    fromInput: @{ @input }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(5))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        // After transformation, $input should be the transformed value (50)
        assertEquals(JsonPrimitive(50), output["fromInput"])
    }

    // ========================================
    // Parent Scope Variables (Loop Variables)
    // ========================================

    @Test
    fun `expressions in for loop can access $item from parent scope`() = runTest {
        val doYaml = """
            do:
              - iterate:
                  for:
                    in: @{ [10, 20, 30] }
                  do:
                    - process:
                        set:
                          itemValue: @{ @item }
                          doubled: @{ @item * 2 }
                  output:
                    as: @{ . }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(30), output["itemValue"])
        assertEquals(JsonPrimitive(60), output["doubled"])
    }

    @Test
    fun `expressions in for loop can access $index from parent scope`() = runTest {
        val doYaml = """
            do:
              - iterate:
                  for:
                    in: @{ ["a", "b", "c"] }
                  do:
                    - process:
                        set:
                          currentIndex: @{ @index }
                          currentItem: @{ @item }
                  output:
                    as: @{ . }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(2), output["currentIndex"])
        assertEquals(JsonPrimitive("c"), output["currentItem"])
    }

    @Test
    fun `expressions in for loop can access custom named variables`() = runTest {
        val doYaml = """
            do:
              - iterate:
                  for:
                    each: number
                    at: position
                    in: @{ [100, 200, 300] }
                  do:
                    - process:
                        set:
                          pos: @{ @position }
                          val: @{ @number }
                  output:
                    as: @{ . }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(2), output["pos"])
        assertEquals(JsonPrimitive(300), output["val"])
    }

    @Test
    fun `nested for loops can access parent and grandparent scope variables`() = runTest {
        val doYaml = """
            do:
              - outerLoop:
                  for:
                    each: outerItem
                    at: outerIndex
                    in: @{ [1, 2] }
                  do:
                    - innerLoop:
                        for:
                          each: innerItem
                          at: innerIndex
                          in: @{ [10, 20] }
                        do:
                          - compute:
                              set:
                                result: @{ @outerItem * 100 + @innerItem }
                  output:
                    as: @{ .result }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput
        // Last iteration: outerItem=2, innerItem=20 => 2*100 + 20 = 220
        assertEquals(JsonPrimitive(220), output)
    }

    // ========================================
    // Scope in Conditions (if, switch, while)
    // ========================================

    @Test
    fun `if condition can access $task descriptor`() = runTest {
        val doYaml = """
            do:
              - checkName:
                  if: @{ @task.name == "checkName" }
                  set:
                    executed: true
              - shouldNotRun:
                  if: @{ @task.name == "wrongName" }
                  set:
                    executed: false
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertEquals(JsonPrimitive(true), output["executed"])
    }

    @Test
    fun `if condition can access $input`() = runTest {
        val doYaml = """
            do:
              - conditionalTask:
                  if: @{ @input > 50 }
                  set:
                    result: "high"
              - elseTask:
                  if: @{ @input <= 50 }
                  set:
                    result: "low"
        """
        val high = getWorkflowProcessor(doYaml, JsonPrimitive(75))
        high.run()
        assertEquals(JsonPrimitive("high"), (high.rootInstance.transformedOutput as JsonObject)["result"])

        val low = getWorkflowProcessor(doYaml, JsonPrimitive(25))
        low.run()
        assertEquals(JsonPrimitive("low"), (low.rootInstance.transformedOutput as JsonObject)["result"])
    }

    @Test
    fun `switch when condition can access $input and $task`() = runTest {
        val doYaml = """
            do:
              - checkValue:
                  switch:
                    - highCase:
                        when: @{ @input > 100 and @task.name == "checkValue" }
                        then: highTask
                    - lowCase:
                        when: @{ @input <= 100 }
                        then: lowTask
              - highTask:
                  set:
                    result: "high"
                  then: exit
              - lowTask:
                  set:
                    result: "low"
        """
        val high = getWorkflowProcessor(doYaml, JsonPrimitive(150))
        high.run()
        assertEquals(JsonPrimitive("high"), (high.rootInstance.transformedOutput as JsonObject)["result"])

        val low = getWorkflowProcessor(doYaml, JsonPrimitive(50))
        low.run()
        assertEquals(JsonPrimitive("low"), (low.rootInstance.transformedOutput as JsonObject)["result"])
    }


    // ========================================
    // Complex Nested Scenarios
    // ========================================


    @Test
    fun `scope provides access to workflow descriptor`() = runTest {
        val doYaml = """
            do:
              - checkWorkflow:
                  set:
                    hasWorkflowId: @{ @workflow.id != null }
                    hasWorkflowInput: @{ @workflow.input != null }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(42))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertTrue((output["hasWorkflowId"] as JsonPrimitive).boolean)
        assertTrue((output["hasWorkflowInput"] as JsonPrimitive).boolean)
    }

    @Test
    fun `scope provides access to runtime descriptor`() = runTest {
        val doYaml = """
            do:
              - checkRuntime:
                  set:
                    hasRuntime: @{ @runtime != null }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        assertTrue((output["hasRuntime"] as JsonPrimitive).boolean)
    }


    @Test
    fun `scope in deeply nested structure maintains all parent variables`() = runTest {
        val doYaml = """
            do:
              - level1:
                  for:
                    each: l1Item
                    in: @{ [10, 20] }
                  do:
                    - level2:
                        for:
                          each: l2Item
                          in: @{ [1, 2] }
                        do:
                          - level3:
                              set:
                                combined: @{ @l1Item + @l2Item }
                                taskName: @{ @task.name }
                        output:
                          as: @{ . }
                  output:
                    as: @{ . }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(emptyMap()))
        instance.run()

        val output = instance.rootInstance.transformedOutput as JsonObject
        // Last iteration: l1Item=20, l2Item=2 => 20+2 = 22
        assertEquals(JsonPrimitive(22), output["combined"])
        assertEquals(JsonPrimitive("level3"), output["taskName"])
    }

}
