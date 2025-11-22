// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for ForTask execution.
 *
 * Tests iteration with loop variables ($item, $index),
 * verifying proper data accumulation and scope management.
 */
@ExperimentalTime
class ForTaskExecutionTest : FunSpec() {

    init {
        test("for task iterates over simple array") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    sum: 0
              - loop:
                  for:
                    in: ${ [1, 2, 3, 4, 5] }
                  do:
                    - add:
                        set:
                          sum: ${ .sum + $item }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["sum"]?.jsonPrimitive?.int shouldBe 15
        }

        test("for task can access item and index") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    items: []
              - loop:
                  for:
                    in: ${ ["a", "b", "c"] }
                  do:
                    - collect:
                        set:
                          items: '${ .items + [{value: $item, index: $index}] }'
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val items = output["items"]?.jsonArray
            items?.size shouldBe 3
            items?.get(0)?.jsonObject?.get("value")?.jsonPrimitive?.content shouldBe "a"
            items?.get(0)?.jsonObject?.get("index")?.jsonPrimitive?.int shouldBe 0
            items?.get(2)?.jsonObject?.get("value")?.jsonPrimitive?.content shouldBe "c"
            items?.get(2)?.jsonObject?.get("index")?.jsonPrimitive?.int shouldBe 2
        }

        test("for task can filter items with if") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    evens: []
              - loop:
                  for:
                    in: ${ [1, 2, 3, 4, 5, 6] }
                  do:
                    - addEven:
                        if: ${ $item % 2 == 0 }
                        set:
                          evens: ${ .evens + [$item] }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val evens = output["evens"]?.jsonArray
            evens?.size shouldBe 3
            evens?.get(0)?.jsonPrimitive?.int shouldBe 2
            evens?.get(1)?.jsonPrimitive?.int shouldBe 4
            evens?.get(2)?.jsonPrimitive?.int shouldBe 6
        }

        test("for task can iterate over expression result") {
            val yaml = $$"""
            do:
              - setup:
                  set:
                    total: 0
                    data:
                      numbers: ${ [10, 20, 30] }
              - loop:
                  for:
                    in: ${ .data.numbers }
                  do:
                    - add:
                        set:
                          total: ${ .total + $item }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            output["total"]?.jsonPrimitive?.int shouldBe 60
        }

        test("for task can build array from items") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    doubled: []
              - loop:
                  for:
                    in: ${ [1, 2, 3] }
                  do:
                    - transform:
                        set:
                          doubled: ${ .doubled + [$item * 2] }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val doubled = output["doubled"]?.jsonArray
            doubled?.size shouldBe 3
            doubled?.get(0)?.jsonPrimitive?.int shouldBe 2
            doubled?.get(1)?.jsonPrimitive?.int shouldBe 4
            doubled?.get(2)?.jsonPrimitive?.int shouldBe 6
        }

        test("nested for loops work correctly") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    pairs: []
              - outer:
                  for:
                    in: ${ [1, 2] }
                  do:
                    - inner:
                        for:
                          in: ${ ["a", "b"] }
                        do:
                          - waiting:
                              wait:
                                milliseconds: 10
                          - combine:
                              set:
                                pairs: '${ .pairs + [{num: $item, letter: $item}] }'
                        output:
                          as: ${ . }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val pairs = output["pairs"]?.jsonArray
            pairs?.size shouldBe 4
        }

        test("for task with object iteration") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    names: []
              - loop:
                  for:
                    in: '${ [{name: "Alice", age: 30}, {name: "Bob", age: 25}] }'
                  do:
                    - extract:
                        set:
                          names: ${ .names + [$item.name] }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val names = output["names"]?.jsonArray
            names?.size shouldBe 2
            names?.get(0)?.jsonPrimitive?.content shouldBe "Alice"
            names?.get(1)?.jsonPrimitive?.content shouldBe "Bob"
        }

        test("for task can access task metadata") {
            val yaml = $$"""
            do:
              - init:
                  set:
                    taskNames: []
              - myLoop:
                  for:
                    in: ${ [1, 2] }
                  do:
                    - capture:
                        set:
                          taskNames: ${ .taskNames + [$task.name] }
                  output:
                    as: ${ . }
        """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            val taskNames = output["taskNames"]?.jsonArray
            taskNames?.size shouldBe 2
            taskNames?.get(0)?.jsonPrimitive?.content shouldBe "capture"
            taskNames?.get(1)?.jsonPrimitive?.content shouldBe "capture"
        }
    }
}
