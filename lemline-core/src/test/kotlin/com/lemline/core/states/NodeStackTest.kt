// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.random.random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

@ExperimentalTime
class NodeStackTest {

    @Test
    fun `should serialize and deserialize preserving hierarchy`() {
        // Given - a hierarchical stack
        val root = NodePosition.root
        val child = root.addName("firstTask")
        val grandchild = child.addName("nestedTask")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                child to DoState(startedAt = Clock.System.now(), index = 0),
                grandchild to TaskState(startedAt = Clock.System.now())
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then
        assertEquals(stack, deserialized)
    }

    @Test
    fun `should serialize only segment names not full paths`() {
        // Given - a hierarchical stack
        val root = NodePosition.root
        val child = root.addName("myTask")
        val grandchild = child.addName("nestedTask")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                child to DoState(startedAt = Clock.System.now(), index = 0),
                grandchild to TaskState(startedAt = Clock.System.now())
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)

        // Then - should contain segment names, not full paths like "/myTask/nestedTask"
        assertTrue(serialized.contains("\"myTask\""), "Should contain segment 'myTask'")
        assertTrue(serialized.contains("\"nestedTask\""), "Should contain segment 'nestedTask'")
        // Should NOT contain the full path notation
        assertTrue(!serialized.contains("\"/myTask\""), "Should not contain full path '/myTask'")
        assertTrue(!serialized.contains("\"/myTask/nestedTask\""), "Should not contain full path")
    }

    @Test
    fun `should handle deep hierarchies`() {
        // Given - a deep stack
        var position = NodePosition.root
        val frames = mutableListOf<Pair<NodePosition, NodeState>>(
            position to RootState(
                startedAt = Clock.System.now(),
                workflowId = WorkflowId.random(),
                workflowInput = JsonPrimitive("test")
            )
        )

        repeat(5) { i ->
            position = position.addName("level$i")
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
        // Given - a stack that skips segment (common in try/catch execution)
        // This happens when entering catch block: /do/tryBlock -> /do/tryBlock/catch/do
        val root = NodePosition.root
        val aPos = NodePosition("/do")
        val bPos = NodePosition("/do/a/aBis")
        val cPos = NodePosition("/do/a/aBis/b/bBis/b/Ter")

        val stack = NodeStack(
            listOf(
                root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = WorkflowId.random(),
                    workflowInput = JsonPrimitive("test")
                ),
                aPos to DoState(startedAt = Clock.System.now(), index = 0),
                bPos to TryState(
                    startedAt = Clock.System.now(),
                    transformedInput = JsonPrimitive("input"),
                    attemptIndex = 0,
                    runningCatch = true,
                    lastError = null,
                    errorAs = "error"
                ),
                cPos to DoState(startedAt = Clock.System.now(), index = 0)
            )
        )

        // When
        val serialized = LemlineJson.encodeToString(stack)
        val deserialized = LemlineJson.decodeFromString<NodeStack>(serialized)

        // Then - the catch/do path should be preserved, not become just /do/tryBlock/do
        assertEquals(stack, deserialized)
        assertEquals(cPos, deserialized.currentPosition)
    }
}
