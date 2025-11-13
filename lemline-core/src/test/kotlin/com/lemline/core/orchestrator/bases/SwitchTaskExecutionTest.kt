// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for SwitchTask execution.
 *
 * Tests conditional branching based on when conditions,
 * verifying proper scope access and control flow.
 */
@ExperimentalTime
abstract class SwitchTaskExecutionTest : FunSpec() {

    init {
        test("switch task executes first matching case") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(75)) as JsonObject

            output["category"]?.jsonPrimitive?.content shouldBe "medium"
        }

        test("switch task executes default case when no condition matches") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(50)) as JsonObject

            output["result"]?.jsonPrimitive?.content shouldBe "default"
        }

        test("switch task can access scope variables in conditions") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(80)) as JsonObject

            output["status"]?.jsonPrimitive?.content shouldBe "above"
        }

        test("switch task can use complex conditions") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["access"]?.jsonPrimitive?.content shouldBe "full"
        }

        test("switch task with string comparison") {
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
            val output = executeWorkflow(yaml, JsonPrimitive("active")) as JsonObject

            output["status"]?.jsonPrimitive?.content shouldBe "running"
        }

        test("switch task can use numeric ranges") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(85)) as JsonObject

            output["grade"]?.jsonPrimitive?.content shouldBe "B"
        }

        test("switch task can access task metadata") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(0)) as JsonObject

            output["matched"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
        }

        test("switch task with null checking") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["result"]?.jsonPrimitive?.content?.toInt() shouldBe 42
        }

        test("nested switch tasks work correctly") {
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
            val output = executeWorkflow(yaml, JsonPrimitive(90)) as JsonObject

            output["level"]?.jsonPrimitive?.content shouldBe "very high"
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
