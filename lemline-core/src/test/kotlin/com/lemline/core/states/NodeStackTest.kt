// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token
import com.lemline.common.values.WorkflowId
import com.lemline.core.random.random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@ExperimentalTime
class NodeStackTest {

    @Test
    fun `should serialize and deserialize preserving hierarchy`() {
        // Given - a hierarchical stack with RFC 6901 compliant positions
        val root = NodePosition.root
        val doPos = root.addToken(Token.DO)
        val taskPos = doPos.addIndex(0).addName("firstTask")
        val nestedPos = taskPos.addToken(Token.DO).addIndex(0).addName("nestedTask")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                doPos to DoState(startedAt = Clock.System.now(), index = 0),
                taskPos to DoState(startedAt = Clock.System.now(), index = 0),
                nestedPos to TaskState(startedAt = Clock.System.now())
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then
        assertEquals(stack, deserialized)
        assertEquals("/do/0/firstTask/do/0/nestedTask", deserialized.currentPosition.toString())
    }

    @Test
    fun `should serialize only segment names not full paths`() {
        // Given - RFC 6901 compliant positions: /do/0/myTask/do/0/nestedTask
        val root = NodePosition.root
        val doPos = root.addToken(Token.DO)
        val taskPos = doPos.addIndex(0).addName("myTask")
        val nestedPos = taskPos.addToken(Token.DO).addIndex(0).addName("nestedTask")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                doPos to DoState(startedAt = Clock.System.now(), index = 0),
                taskPos to DoState(startedAt = Clock.System.now(), index = 0),
                nestedPos to TaskState(startedAt = Clock.System.now())
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)

        // Then - should contain relative segments, not full paths
        assertTrue(serialized.contains("\"do\""), "Should contain segment 'do'")
        assertTrue(serialized.contains("\"0/myTask\""), "Should contain segment '0/myTask'")
        assertTrue(!serialized.contains("\"/do\""), "Should not contain full path '/do'")
        assertTrue(!serialized.contains("\"/do/0/myTask\""), "Should not contain full path")
    }

    @Test
    fun `should handle deep hierarchies`() {
        // Given - a deep stack with RFC 6901 compliant positions
        var position = NodePosition.root
        val frames = mutableListOf<Pair<NodePosition, NodeState>>(
            position to RootState(
                startedAt = Clock.System.now(),
                workflowId = WorkflowId.random(),
                workflowInput = JsonPrimitive("test")
            )
        )

        repeat(5) { i ->
            position = position.addToken(Token.DO).addIndex(i).addName("level$i")
            frames.add(position to DoState(startedAt = Clock.System.now(), index = i))
        }

        val stack = NodeStack(frames)

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then
        assertEquals(stack, deserialized)
        assertEquals(6, deserialized.map { it }.size) // root + 5 levels
    }

    @Test
    fun `should round-trip random stacks`() {
        repeat(20) {
            // Given
            val stack = NodeStack.random()

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
        }
    }

    @Test
    fun `should handle root-only stack`() {
        // Given - only root
        val stack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                )
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then
        assertEquals(stack, deserialized)
    }

    @Test
    fun `should handle paths with skipped segments`() {
        // Given - a stack that skips segments (common in try/catch execution)
        // This happens when entering catch block: /do/0/tryTask -> /do/0/tryTask/catch/do
        // The stack doesn't include intermediate positions like /do/0/tryTask/try
        val root = NodePosition.root
        val doPos = root.addToken(Token.DO)
        val tryTaskPos = doPos.addIndex(0).addName("tryTask")
        val catchDoPos = tryTaskPos.addToken(Token.CATCH).addToken(Token.DO)
        val recoveryPos = catchDoPos.addIndex(0).addName("recovery")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                doPos to DoState(startedAt = Clock.System.now(), index = 0),
                tryTaskPos to TryState(
                    startedAt = Clock.System.now(),
                    transformedInput = JsonPrimitive("input"),
                    attemptIndex = 0,
                    runningCatch = true,
                    lastError = null,
                    errorAs = "error"
                ),
                recoveryPos to TaskState(startedAt = Clock.System.now())
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then - the catch/do path should be preserved correctly
        assertEquals(stack, deserialized)
        assertEquals("/do/0/tryTask/catch/do/0/recovery", deserialized.currentPosition.toString())
    }

    // ========================================
    // RFC 6901 Compliant Positions with Indices
    // ========================================

    @Nested
    inner class Rfc6901ComplianceTests {

        @Test
        fun `should serialize and deserialize positions with array indices`() {
            // Given - RFC 6901 compliant stack: /do/0/taskA
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val indexPos = doPos.addIndex(0)
            val taskPos = indexPos.addName("taskA")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    taskPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/0/taskA", deserialized.currentPosition.toString())
        }

        @Test
        fun `should serialize indices as relative segments`() {
            // Given - /do/0/taskA position
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val taskPos = doPos.addIndex(0).addName("taskA")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    taskPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)

            // Then - should contain relative segments "do" and "0/taskA"
            assertTrue(serialized.contains("\"do\""), "Should contain segment 'do'")
            assertTrue(serialized.contains("\"0/taskA\""), "Should contain segment '0/taskA'")
        }

        @Test
        fun `should handle nested do blocks with indices`() {
            // Given - /do/0/outer/try/do/1/inner
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val outerPos = doPos.addIndex(0).addName("outer")
            val tryPos = outerPos.addToken(Token.TRY)
            val tryDoPos = tryPos.addToken(Token.DO)
            val innerPos = tryDoPos.addIndex(1).addName("inner")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    outerPos to TryState(
                        startedAt = Clock.System.now(),
                        transformedInput = JsonPrimitive("input"),
                        attemptIndex = 0,
                        runningCatch = false,
                        lastError = null,
                        errorAs = "error"
                    ),
                    tryDoPos to DoState(startedAt = Clock.System.now(), index = 1),
                    innerPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/0/outer/try/do/1/inner", deserialized.currentPosition.toString())
        }

        @Test
        fun `should handle fork branches with indices`() {
            // Given - /do/0/forkTask/fork/branches/2/branchC
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val forkTaskPos = doPos.addIndex(0).addName("forkTask")
            val forkPos = forkTaskPos.addToken(Token.FORK)
            val branchesPos = forkPos.addToken(Token.BRANCHES)
            val branchPos = branchesPos.addIndex(2).addName("branchC")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    forkTaskPos to ForkState(startedAt = Clock.System.now()),
                    branchPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/0/forkTask/fork/branches/2/branchC", deserialized.currentPosition.toString())
        }

        @Test
        fun `should handle multiple sequential tasks with different indices`() {
            // Given - simulating execution at /do/2/thirdTask after first two tasks completed
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val taskPos = doPos.addIndex(2).addName("thirdTask")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 2),
                    taskPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/2/thirdTask", deserialized.currentPosition.toString())
        }

        @Test
        fun `should handle for loop with iteration index`() {
            // Given - /do/0/processItems/for/do/0/processItem
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val forTaskPos = doPos.addIndex(0).addName("processItems")
            val forPos = forTaskPos.addToken(Token.FOR)
            val forDoPos = forPos.addToken(Token.DO)
            val itemPos = forDoPos.addIndex(0).addName("processItem")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    forTaskPos to ForState(
                        startedAt = Clock.System.now(),
                        collection = listOf(JsonPrimitive(1), JsonPrimitive(2)),
                        index = 0,
                        forEach = "item",
                        forAt = "index"
                    ),
                    forDoPos to DoState(startedAt = Clock.System.now(), index = 0),
                    itemPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/0/processItems/for/do/0/processItem", deserialized.currentPosition.toString())
        }

        @Test
        fun `should serialize position correctly when index follows a token`() {
            // Given - /do/0/taskA where index comes right after "do" token
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val taskPos = doPos.addIndex(0).addName("taskA")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    taskPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)

            // Then - verify correct structure
            // The serialized JSON should have: [{"":...}, {"do":...}, {"0/taskA":...}]
            assertTrue(
                serialized.contains("\"0/taskA\"") || serialized.contains("\"0\\/taskA\""),
                "Should contain index and task name as relative suffix. Got: $serialized"
            )
        }

        @Test
        fun `should handle deeply nested structure with multiple indices`() {
            // Given - /do/0/outer/do/1/middle/do/2/inner
            val root = NodePosition.root
            val doPos = root.addToken(Token.DO)
            val outerPos = doPos.addIndex(0).addName("outer")
            val outerDoPos = outerPos.addToken(Token.DO)
            val middlePos = outerDoPos.addIndex(1).addName("middle")
            val middleDoPos = middlePos.addToken(Token.DO)
            val innerPos = middleDoPos.addIndex(2).addName("inner")

            val stack = NodeStack(
                listOf(
                    root to RootState(
                        startedAt = Clock.System.now(),
                        workflowId = WorkflowId.random(),
                        workflowInput = JsonPrimitive("test")
                    ),
                    doPos to DoState(startedAt = Clock.System.now(), index = 0),
                    outerPos to DoState(startedAt = Clock.System.now(), index = 0),
                    middlePos to DoState(startedAt = Clock.System.now(), index = 1),
                    innerPos to TaskState(startedAt = Clock.System.now())
                )
            )

            // When
            val serialized = LemlineJson.encodeToString(stack)
            val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

            // Then
            assertEquals(stack, deserialized)
            assertEquals("/do/0/outer/do/1/middle/do/2/inner", deserialized.currentPosition.toString())
        }
    }
}
