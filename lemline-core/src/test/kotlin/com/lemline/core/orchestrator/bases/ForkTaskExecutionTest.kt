// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for ForkTask execution with complete workflows.
 *
 * Tests the parallel execution of tasks within a fork,
 * verifying proper branching and result assembly.
 */
@ExperimentalTime
abstract class ForkTaskExecutionTest : AbstractExecutionTest() {

    init {
        test("fork task executes branches in parallel (cooperative mode)") {
            val yaml = $$"""
            do:
              - parallelWork:
                  fork:
                    compete: false
                    branches:
                      - branch1:
                          set:
                            result: "A"
                      - branch2:
                          set:
                            result: "B"
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            output.shouldBeInstanceOf<JsonArray>()
            val array = output as JsonArray
            array.size shouldBe 2
            array[0].jsonObject["result"]?.jsonPrimitive?.content shouldBe "A"
            array[1].jsonObject["result"]?.jsonPrimitive?.content shouldBe "B"
        }

        test("fork task returns first completed branch in compete mode") {
            val yaml = $$"""
            do:
              - raceWork:
                  fork:
                    compete: true
                    branches:
                      - branch1:
                          set:
                            winner: "first"
                      - branch2:
                          set:
                            winner: "second"
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // In compete mode, we get a single result (not an array)
            output["winner"]?.jsonPrimitive?.content shouldBe "first"
        }

        test("fork task passes same input to all branches") {
            val yaml = $$"""
            do:
              - parallelProcess:
                  input:
                    from:
                      value: 42
                  fork:
                    branches:
                      - branch1:
                          set:
                            fromInput: ${ .value }
                      - branch2:
                          set:
                            fromInput: ${ .value }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonArray

            output[0].jsonObject["fromInput"]?.jsonPrimitive?.int shouldBe 42
            output[1].jsonObject["fromInput"]?.jsonPrimitive?.int shouldBe 42
        }

        test("fork task preserves output order in cooperative mode") {
            val yaml = $$"""
            do:
              - orderedFork:
                  fork:
                    branches:
                      - first:
                          set:
                            order: 1
                      - second:
                          set:
                            order: 2
                      - third:
                          set:
                            order: 3
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonArray

            output.size shouldBe 3
            output[0].jsonObject["order"]?.jsonPrimitive?.int shouldBe 1
            output[1].jsonObject["order"]?.jsonPrimitive?.int shouldBe 2
            output[2].jsonObject["order"]?.jsonPrimitive?.int shouldBe 3
        }

        test("fork task supports output transformation") {
            val yaml = $$"""
            do:
              - transformedFork:
                  fork:
                    branches:
                      - branch1:
                          set:
                            value: 10
                      - branch2:
                          set:
                            value: 20
                  output:
                    as:
                      total: ${ .[0].value + .[1].value }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["total"]?.jsonPrimitive?.int shouldBe 30
        }

        test("fork task supports nested control flow in branches") {
            val yaml = $$"""
            do:
              - nestedFork:
                  fork:
                    branches:
                      - withDo:
                          do:
                            - step1:
                                set:
                                  a: 1
                            - step2:
                                set:
                                  b: 2
                      - withSet:
                          set:
                            c: 3
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonArray

            output.size shouldBe 2
            output[0].jsonObject["b"]?.jsonPrimitive?.int shouldBe 2
            output[1].jsonObject["c"]?.jsonPrimitive?.int shouldBe 3
        }

        test("fork task respects if condition") {
            val yaml = $$"""
            do:
              - conditionalFork:
                  if: ${ .shouldRun }
                  fork:
                    branches:
                      - branch1:
                          set:
                            executed: true
        """
            // Test with shouldRun: true → fork executes
            val output1 = executeWorkflow(yaml, JsonObject(mapOf("shouldRun" to JsonPrimitive(true)))) as JsonArray
            output1[0].jsonObject["executed"]?.jsonPrimitive?.content shouldBe "true"

            // Test with shouldRun: false → fork skipped
            val output2 = executeWorkflow(yaml, JsonObject(mapOf("shouldRun" to JsonPrimitive(false)))) as JsonObject
            output2["shouldRun"]?.jsonPrimitive?.content shouldBe "false" // Original input preserved
        }

        test("fork task with single branch") {
            val yaml = $$"""
            do:
              - singleBranchFork:
                  fork:
                    branches:
                      - onlyBranch:
                          set:
                            result: "solo"
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonArray

            output.size shouldBe 1
            output[0].jsonObject["result"]?.jsonPrimitive?.content shouldBe "solo"
        }

        test("fork task supports export context") {
            val yaml = $$"""
            do:
              - forkWithExport:
                  fork:
                    branches:
                      - branch1:
                          set:
                            value: 10
                      - branch2:
                          set:
                            value: 20
                  export:
                    as:
                      forkResults: .
              - useExport:
                  set:
                    total: ${ $context.forkResults[0].value + $context.forkResults[1].value }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            // The final task should have access to the fork results via exported context
            output["total"]?.jsonPrimitive?.int shouldBe 30
        }

        test("fork task with empty branches defaults to cooperative mode") {
            val yaml = $$"""
            do:
              - defaultModeFork:
                  fork:
                    branches:
                      - branch1:
                          set:
                            mode: "cooperative"
                      - branch2:
                          set:
                            mode: "cooperative"
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            // Should return array (cooperative mode is default)
            output.shouldBeInstanceOf<JsonArray>()
            (output as JsonArray).size shouldBe 2
        }
    }
}
