// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

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
                output == buildJsonObject { put("sum", 15) }
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
                output == buildJsonObject {
                    put("items", buildJsonArray {
                        add(buildJsonObject { put("value", "a"); put("index", 0) })
                        add(buildJsonObject { put("value", "b"); put("index", 1) })
                        add(buildJsonObject { put("value", "c"); put("index", 2) })
                    })
                }
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
                output == buildJsonObject {
                    put("evens", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(4), JsonPrimitive(6))))
                }
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
                output == buildJsonObject { put("total", 60) }
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
                output == buildJsonObject {
                    put("doubled", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(4), JsonPrimitive(6))))
                }
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
                output == buildJsonObject {
                    put("names", JsonArray(listOf(JsonPrimitive("Alice"), JsonPrimitive("Bob"))))
                }
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
                output == buildJsonObject {
                    put("taskNames", JsonArray(listOf(JsonPrimitive("capture"), JsonPrimitive("capture"))))
                }
            }
        )
    )
}
