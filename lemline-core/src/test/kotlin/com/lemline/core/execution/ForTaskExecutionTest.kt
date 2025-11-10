// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for ForTask execution using ExecutionOrchestrator.
 *
 * Tests iteration with loop variables (@item, @index),
 * verifying proper data accumulation and scope management.
 */
@ExperimentalTime
class ForTaskExecutionTest {

    @Test
    fun `for task iterates over simple array`() = runTest {
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
                          sum: ${ .sum + @item }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(15, output["sum"]?.jsonPrimitive?.int)
    }

    @Test
    fun `for task can access item and index`() = runTest {
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
                          items: '${ .items + [{value: @item, index: @index}] }'
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val items = output["items"]?.jsonArray
        assertEquals(3, items?.size)
        assertEquals("a", items?.get(0)?.jsonObject?.get("value")?.jsonPrimitive?.content)
        assertEquals(0, items?.get(0)?.jsonObject?.get("index")?.jsonPrimitive?.int)
        assertEquals("c", items?.get(2)?.jsonObject?.get("value")?.jsonPrimitive?.content)
        assertEquals(2, items?.get(2)?.jsonObject?.get("index")?.jsonPrimitive?.int)
    }

    @Test
    fun `for task can filter items with if`() = runTest {
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
                        if: ${ @item % 2 == 0 }
                        set:
                          evens: ${ .evens + [@item] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val evens = output["evens"]?.jsonArray
        assertEquals(3, evens?.size)
        assertEquals(2, evens?.get(0)?.jsonPrimitive?.int)
        assertEquals(4, evens?.get(1)?.jsonPrimitive?.int)
        assertEquals(6, evens?.get(2)?.jsonPrimitive?.int)
    }

    @Test
    fun `for task can iterate over expression result`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    data:
                      numbers: [10, 20, 30]
              - init:
                  set:
                    total: 0
              - loop:
                  for:
                    in: ${ .data.numbers }
                  do:
                    - add:
                        set:
                          total: ${ .total + @item }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(60, output["total"]?.jsonPrimitive?.int)
    }

    @Test
    fun `for task can build array from items`() = runTest {
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
                          doubled: ${ .doubled + [@item * 2] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val doubled = output["doubled"]?.jsonArray
        assertEquals(3, doubled?.size)
        assertEquals(2, doubled?.get(0)?.jsonPrimitive?.int)
        assertEquals(4, doubled?.get(1)?.jsonPrimitive?.int)
        assertEquals(6, doubled?.get(2)?.jsonPrimitive?.int)
    }

    @Test
    fun `for task preserves data from previous tasks`() = runTest {
        val yaml = $$"""
            do:
              - setup:
                  set:
                    multiplier: 10
                    results: []
              - loop:
                  for:
                    in: ${ [1, 2, 3] }
                  do:
                    - calculate:
                        set:
                          results: ${ .results + [@item * .multiplier] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(10, output["multiplier"]?.jsonPrimitive?.int)
        val results = output["results"]?.jsonArray
        assertEquals(3, results?.size)
        assertEquals(10, results?.get(0)?.jsonPrimitive?.int)
        assertEquals(20, results?.get(1)?.jsonPrimitive?.int)
        assertEquals(30, results?.get(2)?.jsonPrimitive?.int)
    }

    @Test
    fun `nested for loops work correctly`() = runTest {
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
                          - combine:
                              set:
                                pairs: '${ .pairs + [{num: @item, letter: @item}] }'
                        output:
                          as: ${ . }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val pairs = output["pairs"]?.jsonArray
        assertEquals(4, pairs?.size)
    }

    @Test
    fun `for task with object iteration`() = runTest {
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
                          names: ${ .names + [@item.name] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val names = output["names"]?.jsonArray
        assertEquals(2, names?.size)
        assertEquals("Alice", names?.get(0)?.jsonPrimitive?.content)
        assertEquals("Bob", names?.get(1)?.jsonPrimitive?.content)
    }

    @Test
    fun `for task can access task metadata`() = runTest {
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
                          taskNames: ${ .taskNames + [@task.name] }
                  output:
                    as: ${ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = ExecutionOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        val taskNames = output["taskNames"]?.jsonArray
        assertEquals(2, taskNames?.size)
        assertEquals("capture", taskNames?.get(0)?.jsonPrimitive?.content)
        assertEquals("capture", taskNames?.get(1)?.jsonPrimitive?.content)
    }
}
