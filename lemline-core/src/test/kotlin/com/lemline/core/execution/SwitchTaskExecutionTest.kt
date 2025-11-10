// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for SwitchTask execution using ExecutionOrchestrator.
 *
 * Tests conditional branching based on when conditions,
 * verifying proper scope access and control flow.
 */
@ExperimentalTime
class SwitchTaskExecutionTest {

    @Test
    fun `switch task executes first matching case`() = runTest {
        val yaml = $$"""
            do:
              - route:
                  switch:
                    - highCase:
                        when: ${ @input > 100 }
                        then: highTask
                    - mediumCase:
                        when: ${ @input > 50 }
                        then: mediumTask
                    - lowCase:
                        then: lowTask
              - highTask:
                  set:
                    category: ${ "high" }
                  then: end
              - mediumTask:
                  set:
                    category: ${ "medium" }
                  then: end
              - lowTask:
                  set:
                    category: ${ "low" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(75)) as JsonObject

        assertEquals("medium", output["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task executes default case when no condition matches`() = runTest {
        val yaml = $$"""
            do:
              - route:
                  switch:
                    - case1:
                        when: ${ @input > 100 }
                        then: task1
                    - case2:
                        when: ${ @input < 10 }
                        then: task2
                    - defaultCase:
                        then: defaultTask
              - task1:
                  set:
                    result: ${ "high" }
                  then: end
              - task2:
                  set:
                    result: ${ "low" }
                  then: end
              - defaultTask:
                  set:
                    result: ${ "default" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(50)) as JsonObject

        assertEquals("default", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task can access scope variables in conditions`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    threshold: 75
              - route:
                  switch:
                    - aboveThreshold:
                        when: ${ @input > .threshold }
                        then: highTask
                    - belowThreshold:
                        then: lowTask
              - highTask:
                  set:
                    status: ${ "above" }
                  then: end
              - lowTask:
                  set:
                    status: ${ "below" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(80)) as JsonObject

        assertEquals("above", output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task can use complex conditions`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    user:
                      role: "admin"
                      verified: true
              - route:
                  switch:
                    - adminVerified:
                        when: ${ .user.role == "admin" and .user.verified == true }
                        then: adminTask
                    - adminUnverified:
                        when: ${ .user.role == "admin" }
                        then: verifyTask
                    - other:
                        then: userTask
              - adminTask:
                  set:
                    access: ${ "full" }
                  then: end
              - verifyTask:
                  set:
                    access: ${ "limited" }
                  then: end
              - userTask:
                  set:
                    access: ${ "basic" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("full", output["access"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task with string comparison`() = runTest {
        val yaml = $$"""
            do:
              - route:
                  switch:
                    - active:
                        when: ${ @input == "active" }
                        then: activeTask
                    - inactive:
                        when: ${ @input == "inactive" }
                        then: inactiveTask
                    - default:
                        then: unknownTask
              - activeTask:
                  set:
                    status: ${ "running" }
                  then: end
              - inactiveTask:
                  set:
                    status: ${ "stopped" }
                  then: end
              - unknownTask:
                  set:
                    status: ${ "unknown" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive("active")) as JsonObject

        assertEquals("running", output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task can use numeric ranges`() = runTest {
        val yaml = $$"""
            do:
              - gradeScore:
                  switch:
                    - gradeA:
                        when: ${ @input >= 90 }
                        then: setA
                    - gradeB:
                        when: ${ @input >= 80 and @input < 90 }
                        then: setB
                    - gradeC:
                        when: ${ @input >= 70 and @input < 80 }
                        then: setC
                    - gradeF:
                        then: setF
              - setA:
                  set:
                    grade: ${ "A" }
                  then: end
              - setB:
                  set:
                    grade: ${ "B" }
                  then: end
              - setC:
                  set:
                    grade: ${ "C" }
                  then: end
              - setF:
                  set:
                    grade: ${ "F" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(85)) as JsonObject

        assertEquals("B", output["grade"]?.jsonPrimitive?.content)
    }

    @Test
    fun `switch task can access task metadata`() = runTest {
        val yaml = $$"""
            do:
              - router:
                  switch:
                    - checkName:
                        when: ${ @task.name == "router" }
                        then: correctTask
                    - default:
                        then: wrongTask
              - correctTask:
                  set:
                    matched: ${ true }
                  then: end
              - wrongTask:
                  set:
                    matched: ${ false }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(0)) as JsonObject

        assertEquals(true, output["matched"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `switch task with null checking`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    data:
                      value: 42
              - checkValue:
                  switch:
                    - hasValue:
                        when: ${ .data.value != null }
                        then: processValue
                    - noValue:
                        then: handleNull
              - processValue:
                  set:
                    result: ${ .data.value }
                  then: end
              - handleNull:
                  set:
                    result: ${ 0 }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(42, output["result"]?.jsonPrimitive?.int)
    }

    @Test
    fun `nested switch tasks work correctly`() = runTest {
        val yaml = $$"""
            do:
              - outerSwitch:
                  switch:
                    - case1:
                        when: ${ @input > 50 }
                        then: innerSwitch
                    - default:
                        then: lowTask
              - innerSwitch:
                  switch:
                    - veryHigh:
                        when: ${ @input > 80 }
                        then: veryHighTask
                    - high:
                        then: highTask
              - veryHighTask:
                  set:
                    level: ${ "very high" }
                  then: end
              - highTask:
                  set:
                    level: ${ "high" }
                  then: end
              - lowTask:
                  set:
                    level: ${ "low" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonPrimitive(90)) as JsonObject

        assertEquals("very high", output["level"]?.jsonPrimitive?.content)
    }
}
