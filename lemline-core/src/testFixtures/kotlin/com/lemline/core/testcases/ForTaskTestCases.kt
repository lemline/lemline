// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for ForTask execution.
 * Tests iteration with loop variables ($item, $index).
 */
object ForTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "for task iterates over simple array",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("sum=15") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["sum"]?.jsonPrimitive?.int == 15
            }
        ),

        WorkflowTestCase(
            name = "for task can access item and index",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("items array with value/index pairs") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val items = obj["items"]?.jsonArray ?: return@expectOutputMatching false
                items.size == 3 &&
                    items[0].jsonObject["value"]?.jsonPrimitive?.content == "a" &&
                    items[0].jsonObject["index"]?.jsonPrimitive?.int == 0 &&
                    items[2].jsonObject["value"]?.jsonPrimitive?.content == "c" &&
                    items[2].jsonObject["index"]?.jsonPrimitive?.int == 2
            }
        ),

        WorkflowTestCase(
            name = "for task can filter items with if",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("evens=[2,4,6]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val evens = obj["evens"]?.jsonArray ?: return@expectOutputMatching false
                evens.size == 3 &&
                    evens[0].jsonPrimitive.int == 2 &&
                    evens[1].jsonPrimitive.int == 4 &&
                    evens[2].jsonPrimitive.int == 6
            }
        ),

        WorkflowTestCase(
            name = "for task can iterate over expression result",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("total=60") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["total"]?.jsonPrimitive?.int == 60
            }
        ),

        WorkflowTestCase(
            name = "for task can build array from items",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("doubled=[2,4,6]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val doubled = obj["doubled"]?.jsonArray ?: return@expectOutputMatching false
                doubled.size == 3 &&
                    doubled[0].jsonPrimitive.int == 2 &&
                    doubled[1].jsonPrimitive.int == 4 &&
                    doubled[2].jsonPrimitive.int == 6
            }
        ),

        WorkflowTestCase(
            name = "nested for loops work correctly",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("pairs has 4 items") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val pairs = obj["pairs"]?.jsonArray ?: return@expectOutputMatching false
                pairs.size == 4
            }
        ),

        WorkflowTestCase(
            name = "for task with object iteration",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("names=[Alice, Bob]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val names = obj["names"]?.jsonArray ?: return@expectOutputMatching false
                names.size == 2 &&
                    names[0].jsonPrimitive.content == "Alice" &&
                    names[1].jsonPrimitive.content == "Bob"
            }
        ),

        WorkflowTestCase(
            name = "for task can access task metadata",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("taskNames=[capture, capture]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val taskNames = obj["taskNames"]?.jsonArray ?: return@expectOutputMatching false
                taskNames.size == 2 &&
                    taskNames[0].jsonPrimitive.content == "capture" &&
                    taskNames[1].jsonPrimitive.content == "capture"
            }
        )
    )
}
