// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for if conditions in task execution.
 *
 * Tests conditional task execution based on if expressions,
 * verifying proper scope access and skipping logic.
 */
@ExperimentalTime
class IfConditionExecutionTest : FunSpec() {

    init {
        test("task with if condition executes when condition is true") {
            val yaml = $$"""
            do:
              - conditionalTask:
                  if: ${ . > 50 }
                  set:
                    result: ${ "executed" }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(75)) as JsonObject

            output["result"]?.jsonPrimitive?.content shouldBe "executed"
        }

        test("task with if condition skips when condition is false") {
            val yaml = $$"""
            do:
              - conditionalTask:
                  if: ${ . > 100 }
                  set:
                    result: ${ "executed" }
              - alwaysTask:
                  set:
                    fallback: ${ "always" }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(50)) as JsonObject

            output["result"].shouldBeNull()
            output["fallback"]?.jsonPrimitive?.content shouldBe "always"
        }

        test("if condition can access previous task data") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    score: ${ . }
                    threshold: 80
              - check:
                  if: ${ .score > .threshold }
                  set:
                    status: ${ "high" }
                  then: exit
              - fallback:
                  if: ${ .score <= .threshold }
                  set:
                    status: ${ "low" }
        """
            val high = executeWorkflow(yaml, JsonPrimitive(90)) as JsonObject

            high["status"]?.jsonPrimitive?.content shouldBe "high"

            val low = executeWorkflow(yaml, JsonPrimitive(70)) as JsonObject

            low["status"]?.jsonPrimitive?.content shouldBe "low"
        }

        test("if condition with string comparison") {
            val yaml = $$"""
            do:
              - checkActive:
                  if: ${ . == "active" }
                  set:
                    status: ${ "running" }
              - checkInactive:
                  if: ${ . == "inactive" }
                  set:
                    status: ${ "stopped" }
        """
            val output = executeWorkflow(yaml, JsonPrimitive("active")) as JsonObject

            output["status"]?.jsonPrimitive?.content shouldBe "running"
        }

        test("if condition with logical AND") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    value: 75
                    enabled: true
              - check:
                  if: ${ .value > 50 and .enabled == true }
                  set:
                    result: ${ "passed" }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["result"]?.jsonPrimitive?.content shouldBe "passed"
        }

        test("if condition with logical OR") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    isAdmin: false
                    isModerator: true
              - check:
                  if: ${ .isAdmin or .isModerator }
                  set:
                    access: ${ "granted" }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["access"]?.jsonPrimitive?.content shouldBe "granted"
        }

        test("if condition can check null values") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    data:
                      value: 42
              - checkExists:
                  if: ${ .data.value != null }
                  set:
                    exists: ${ true }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["exists"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
        }

        test("if condition in for loop") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    evens: []
              - iterate:
                  for:
                    in: ${ [1, 2, 3, 4, 5, 6, 7, 8] }
                  do:
                    - checkEven:
                        if: ${ $item % 2 == 0 }
                        set:
                          evens: ${ .evens + [$item] }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val evens = output["evens"]?.jsonArray
            evens?.size shouldBe 4
            evens?.get(0)?.jsonPrimitive?.int shouldBe 2
            evens?.get(1)?.jsonPrimitive?.int shouldBe 4
            evens?.get(2)?.jsonPrimitive?.int shouldBe 6
            evens?.get(3)?.jsonPrimitive?.int shouldBe 8
        }

        test("if condition can access task metadata") {
            val yaml = $$"""
            do:
              - myTask:
                  if: ${ $task.name == "myTask" }
                  set:
                    matched: ${ true }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["matched"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
        }

        test("multiple if conditions in sequence") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    score: 85
              - checkA:
                  if: ${ .score >= 90 }
                  set:
                    score: .score
                    grade: ${ "A" }
              - checkB:
                  if: ${ .score >= 80 and .score < 90 }
                  set:
                    score: .score
                    grade: ${ "B" }
              - checkC:
                  if: ${ .score >= 70 and .score < 80 }
                  set:
                    score: .score
                    grade: ${ "C" }
              - checkF:
                  if: ${ .score < 70 }
                  set:
                    score: .score
                    grade: ${ "F" }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["grade"]?.jsonPrimitive?.content shouldBe "B"
        }

        test("if condition with complex nested expressions") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    user:
                      role: "admin"
                      level: 5
                      verified: true
              - check:
                  if: ${ (.user.role == "admin" and .user.level > 3) or .user.verified }
                  set:
                    authorized: ${ true }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["authorized"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
        }

        test("if condition preserves data when skipped") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    value: 10
              - conditionalModify:
                  if: ${ .value > 100 }
                  set:
                    value: 999
              - final:
                  set:
                    result: ${ .value }
        """
            val output = executeWorkflow(yaml, JsonPrimitive(50)) as JsonObject

            output["result"]?.jsonPrimitive?.int shouldBe 10
        }
    }
}
