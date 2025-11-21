// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for mapAwaitAllFailFast and mapAwaitFirstFailSlow coroutine utilities.
 */
class MapAwaitTest {

    // ===================================================================================
    // mapAwaitAllFailFast Tests
    // ===================================================================================

    @Nested
    inner class MapAwaitAllFailFastTests {

        @Test
        fun `should return all results when all tasks succeed`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)

            val result = input.mapAwaitAllFailFast { it * 2 }

            assertEquals(listOf(2, 4, 6, 8, 10), result)
        }

        @Test
        fun `should preserve order of results`() = runTest {
            val input = listOf("a", "b", "c", "d", "e")

            // Add varying delays to ensure concurrent execution doesn't affect order
            val result = input.mapAwaitAllFailFast { value ->
                delay((5 - input.indexOf(value)).toLong() * 10)
                value.uppercase()
            }

            assertEquals(listOf("A", "B", "C", "D", "E"), result)
        }

        @Test
        fun `should handle empty list`() = runTest {
            val input = emptyList<Int>()

            val result = input.mapAwaitAllFailFast { it * 2 }

            assertEquals(emptyList(), result)
        }

        @Test
        fun `should handle single element list`() = runTest {
            val input = listOf(42)

            val result = input.mapAwaitAllFailFast { it * 2 }

            assertEquals(listOf(84), result)
        }

        @Test
        fun `should throw exception when single task fails`() = runTest {
            val input = listOf(1, 2, 3)

            val exception = assertFailsWith<IllegalStateException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 2) throw IllegalStateException("Task 2 failed")
                    value
                }
            }

            assertEquals("Task 2 failed", exception.message)
        }

        @Test
        fun `should throw first exception when multiple tasks fail`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)
            val executionOrder = ConcurrentHashMap<Int, Long>()

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    // Task 3 fails first (shorter delay)
                    when (value) {
                        3 -> {
                            delay(50)
                            executionOrder[value] = System.nanoTime()
                            throw RuntimeException("Task 3 failed later")
                        }

                        5 -> {
                            delay(10)
                            executionOrder[value] = System.nanoTime()
                            throw IllegalArgumentException("Task 5 failed first")
                        }

                        else -> {
                            delay(100)
                            value
                        }
                    }
                }
            }

            assertEquals("Task 5 failed first", exception.message)
        }

        @Test
        fun `should preserve exception type`() = runTest {
            val input = listOf(1, 2, 3)

            assertFailsWith<IllegalArgumentException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 2) throw IllegalArgumentException("Custom exception")
                    value
                }
            }
        }

        @Test
        fun `should preserve exception message and cause chain`() = runTest {
            val input = listOf(1, 2, 3)
            val rootCause = NullPointerException("Root cause")

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 2) throw RuntimeException("Wrapper exception", rootCause)
                    value
                }
            }

            // The exception message should be "Wrapper exception"
            assertEquals("Wrapper exception", exception.message)

            // Check that "Root cause" message exists somewhere in the cause chain
            fun findCauseMessage(e: Throwable?, target: String): Boolean {
                if (e == null) return false
                if (e.message == target) return true
                return findCauseMessage(e.cause, target)
            }

            assertTrue(
                findCauseMessage(exception.cause, "Root cause"),
                "Expected 'Root cause' in cause chain. Exception chain: ${
                    generateSequence(exception as Throwable?) { it.cause }.map { it.message }.toList()
                }"
            )
        }

        @Test
        fun `should execute tasks concurrently`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)
            val startTimes = ConcurrentHashMap<Int, Long>()

            withTimeout(500) {
                input.mapAwaitAllFailFast { value ->
                    startTimes[value] = System.currentTimeMillis()
                    delay(50)
                    value
                }
            }

            // All tasks should have started close to each other (within 30ms)
            val times = startTimes.values.toList()
            val maxDiff = times.maxOrNull()!! - times.minOrNull()!!
            assertTrue(maxDiff < 30, "Tasks should start concurrently, but max time diff was $maxDiff ms")
        }

        @Test
        fun `should propagate CancellationException`() = runTest {
            val input = listOf(1, 2, 3)

            assertFailsWith<CancellationException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 2) throw CancellationException("Cancelled")
                    value
                }
            }
        }

        @Test
        fun `should complete quickly when first task fails`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)

            withTimeout(100) {
                assertFailsWith<RuntimeException> {
                    input.mapAwaitAllFailFast { value ->
                        if (value == 1) {
                            throw RuntimeException("Immediate failure")
                        }
                        delay(10000) // Long delay that should not block
                        value
                    }
                }
            }
        }

        @Test
        fun `should handle tasks with different execution times all succeeding`() = runTest {
            val input = listOf(50, 10, 30, 20, 40)

            val result = input.mapAwaitAllFailFast { delayMs ->
                delay(delayMs.toLong())
                delayMs
            }

            // Order should be preserved despite different execution times
            assertEquals(listOf(50, 10, 30, 20, 40), result)
        }

        @Test
        fun `should handle suspend function that returns Unit`() = runTest {
            val input = listOf(1, 2, 3)
            val counter = AtomicInteger(0)

            val result = input.mapAwaitAllFailFast {
                counter.incrementAndGet()
            }

            assertEquals(3, counter.get())
            assertEquals(listOf(1, 2, 3), result)
        }

        @Test
        fun `should handle exception in first task`() = runTest {
            val input = listOf(1, 2, 3)

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 1) throw RuntimeException("First task failed")
                    delay(100)
                    value
                }
            }

            assertEquals("First task failed", exception.message)
        }

        @Test
        fun `should handle exception in last task`() = runTest {
            val input = listOf(1, 2, 3)

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 3) throw RuntimeException("Last task failed")
                    delay(100)
                    value
                }
            }

            assertEquals("Last task failed", exception.message)
        }

        @Test
        fun `should handle all tasks failing`() = runTest {
            val input = listOf(1, 2, 3)

            val e = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    delay(abs(3 - value).toLong() * 10)
                    throw RuntimeException("Task $value failed")
                }
            }
            e.message?.contains("Task 3 failed") ?: fail("should throw the first exception")
        }

        @Test
        fun `should handle nested mapAwaitAllFailFast calls`() = runTest {
            val input = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))

            val result = input.mapAwaitAllFailFast { innerList ->
                innerList.mapAwaitAllFailFast { it * 2 }
            }

            assertEquals(listOf(listOf(2, 4), listOf(6, 8), listOf(10, 12)), result)
        }

        @Test
        fun `should handle exception in nested mapAwaitAllFailFast`() = runTest {
            val input = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))

            assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { innerList ->
                    innerList.mapAwaitAllFailFast { value ->
                        if (value == 4) throw RuntimeException("Nested failure")
                        value
                    }
                }
            }
        }

        @Test
        fun `should verify other coroutines continue after failure`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)
            val completed = ConcurrentHashMap.newKeySet<Int>()

            assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 1) {
                        delay(10)
                        throw RuntimeException("First failed")
                    }
                    delay(50)
                    completed.add(value)
                    value
                }
            }

            // Wait for other coroutines to potentially complete
            delay(100)

            // Some coroutines may have completed since they continue running
            // (This is the documented behavior - "other are still running")
            assertEquals(completed.size, 4, "Other coroutines may or may not complete depending on timing")
        }
    }

    // ===================================================================================
    // mapAwaitFirstFailSlow Tests
    // ===================================================================================

    @Nested
    inner class MapAwaitFirstFailSlowTests {

        @Test
        fun `should return first successful result`() = runTest {
            val input = listOf(1, 2, 3)

            val result = input.mapAwaitFirstFailSlow { value ->
                delay((4 - value).toLong() * 20) // 1 takes longest, 3 is fastest
                value * 10
            }

            assertEquals(30, result) // Task 3 completes first
        }

        @Test
        fun `should return result from fastest task`() = runTest {
            val input = listOf(100, 50, 10, 75)

            val result = input.mapAwaitFirstFailSlow { delayMs ->
                delay(delayMs.toLong())
                delayMs
            }

            assertEquals(10, result) // 10ms delay is fastest
        }

        @Test
        fun `should handle single element list`() = runTest {
            val input = listOf(42)

            val result = input.mapAwaitFirstFailSlow { it * 2 }

            assertEquals(84, result)
        }

        @Test
        fun `should throw exception when all tasks fail`() = runTest {
            val input = listOf(1, 2, 3)

            val e = assertFailsWith<RuntimeException> {
                input.mapAwaitFirstFailSlow { value ->
                    delay(abs(3 - value).toLong() * 10)
                    throw RuntimeException("Task $value failed")
                }
            }
            e.message?.contains("Task 1 failed") ?: fail("should send the last exception")
        }

        @Test
        fun `should succeed if at least one task succeeds`() = runTest {
            val input = listOf(1, 2, 3)

            val result = input.mapAwaitFirstFailSlow { value ->
                if (value != 2) throw RuntimeException("Task $value failed")
                delay(10)
                value * 10
            }

            assertEquals(20, result)
        }

        @Test
        fun `should return first success even when later tasks would fail`() = runTest {
            val input = listOf(1, 2, 3)

            val result = input.mapAwaitFirstFailSlow { value ->
                when (value) {
                    1 -> {
                        delay(10)
                        100 // First success
                    }

                    2 -> {
                        delay(50)
                        throw RuntimeException("Would fail but ignored")
                    }

                    else -> {
                        delay(100)
                        300
                    }
                }
            }

            assertEquals(100, result)
        }

        @Test
        fun `should handle all but one failing`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)

            val result = input.mapAwaitFirstFailSlow { value ->
                if (value != 3) throw RuntimeException("Failed")
                value * 100
            }

            assertEquals(300, result)
        }

        @Test
        fun `should preserve exception type when all fail`() = runTest {
            val input = listOf(1, 2, 3)

            // The last failure's exception is thrown
            assertFailsWith<IllegalArgumentException> {
                input.mapAwaitFirstFailSlow { value ->
                    delay(value.toLong() * 10)
                    throw IllegalArgumentException("Task $value failed")
                }
            }
        }

        @Test
        fun `should execute tasks concurrently`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)
            val startTimes = ConcurrentHashMap<Int, Long>()

            withTimeout(500) {
                input.mapAwaitFirstFailSlow { value ->
                    startTimes[value] = System.currentTimeMillis()
                    delay(50)
                    value
                }
            }

            // All tasks should have started close to each other
            val times = startTimes.values.toList()
            val maxDiff = times.maxOrNull()!! - times.minOrNull()!!
            assertTrue(maxDiff < 30, "Tasks should start concurrently")
        }

        @Test
        fun `should handle null result as success`() = runTest {
            val input = listOf(1, 2, 3)

            val result = input.mapAwaitFirstFailSlow { value ->
                delay((4 - value).toLong() * 10)
                if (value == 3) null else "value $value"
            }

            assertEquals(null, result) // Task 3 (returns null) completes first
        }

        @Test
        fun `should work with complex objects`() = runTest {
            data class Input(val id: Int, val delay: Long)
            data class Output(val id: Int, val result: String)

            val input = listOf(
                Input(1, 100),
                Input(2, 10),  // Fastest
                Input(3, 50)
            )

            val result = input.mapAwaitFirstFailSlow { item ->
                delay(item.delay)
                Output(item.id, "Processed ${item.id}")
            }

            assertEquals(Output(2, "Processed 2"), result)
        }

        @Test
        fun `should throw last exception when all fail with different timings`() = runTest {
            val input = listOf(1, 2, 3)
            val completedFailures = mutableListOf<Int>()

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitFirstFailSlow { value ->
                    delay(value.toLong() * 10) // 1 fails first, 3 fails last
                    synchronized(completedFailures) {
                        completedFailures.add(value)
                    }
                    throw RuntimeException("Task $value failed")
                }
            }

            // The last failure (task 3 due to longest delay) should be thrown
            assertEquals("Task 3 failed", exception.message)
        }

        @Test
        fun `should handle mixed success and failure with timing`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)

            val result = input.mapAwaitFirstFailSlow { value ->
                delay(value.toLong() * 10)
                if (value <= 3) throw RuntimeException("Task $value failed")
                value * 10 // Tasks 4 and 5 succeed, 4 completes first
            }

            assertEquals(40, result)
        }

        @Test
        fun `should throw exception for empty list`() = runTest {
            val input = emptyList<Int>()

            // Empty list should throw an exception since there's nothing to succeed
            val exception = assertFailsWith<IllegalArgumentException> {
                input.mapAwaitFirstFailSlow { it * 2 }
            }

            assertTrue(
                exception.message?.contains("empty") == true || exception.message?.contains("Empty") == true,
                "Expected exception message about empty list, got: ${exception.message}"
            )
        }

        @Test
        fun `should return immediately when fast task succeeds`() = runTest {
            val input = listOf(1, 2, 3)

            withTimeout(100) {
                val result = input.mapAwaitFirstFailSlow { value ->
                    if (value == 1) {
                        value // Immediate success
                    } else {
                        delay(10000) // Long delay that should not block
                        value
                    }
                }

                assertEquals(1, result)
            }
        }

        @Test
        fun `should propagate CancellationException when all cancel`() = runTest {
            val input = listOf(1, 2, 3)

            assertFailsWith<CancellationException> {
                input.mapAwaitFirstFailSlow { value ->
                    throw CancellationException("Task $value cancelled")
                }
            }
        }

        @Test
        fun `should succeed when one task succeeds and others cancel`() = runTest {
            val input = listOf(1, 2, 3)

            val result = input.mapAwaitFirstFailSlow { value ->
                when (value) {
                    1 -> {
                        delay(10)
                        100
                    }

                    else -> throw CancellationException("Cancelled")
                }
            }

            assertEquals(100, result)
        }

        @Test
        fun `should verify other coroutines continue after first success`() = runTest {
            val input = listOf(1, 2, 3, 4, 5)
            val completed = ConcurrentHashMap.newKeySet<Int>()

            input.mapAwaitFirstFailSlow { value ->
                delay(value.toLong() * 20)
                completed.add(value)
                value
            }

            // Wait for other coroutines to potentially complete
            delay(200)

            // Other coroutines should continue running (documented behavior)
            assertEquals(completed.size, 5, "First task should complete")
        }

        @Test
        fun `should handle large list with single success`() = runTest {
            val input = (1..100).toList()

            val result = input.mapAwaitFirstFailSlow { value ->
                if (value != 50) throw RuntimeException("Failed")
                value
            }

            assertEquals(50, result)
        }
    }

    // ===================================================================================
    // Combined/Edge Case Tests
    // ===================================================================================

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `mapAwaitAllFailFast should handle Error (not Exception)`() = runTest {
            val input = listOf(1, 2, 3)

            assertFailsWith<OutOfMemoryError> {
                input.mapAwaitAllFailFast { value ->
                    if (value == 2) throw OutOfMemoryError("Simulated OOM")
                    value
                }
            }
        }

        @Test
        fun `mapAwaitFirstFailSlow should handle Error (not Exception)`() = runTest {
            val input = listOf(1, 2, 3)

            assertFailsWith<OutOfMemoryError> {
                input.mapAwaitFirstFailSlow { value ->
                    throw OutOfMemoryError("Simulated OOM for task $value")
                }
            }
        }

        @Test
        fun `both methods should handle very large delays without actual waiting`() = runTest {
            // This tests that the methods properly handle virtual time in runTest
            val input = listOf(1, 2, 3)

            // This should complete in virtual time, not real time
            val result1 = input.mapAwaitAllFailFast { value ->
                delay(Long.MAX_VALUE / 2)
                value
            }

            val result2 = input.mapAwaitFirstFailSlow { value ->
                if (value == 1) {
                    1
                } else {
                    delay(Long.MAX_VALUE / 2)
                    value
                }
            }

            assertEquals(listOf(1, 2, 3), result1)
            assertEquals(1, result2)
        }

        @Test
        fun `mapAwaitAllFailFast should handle exception with suppressed exceptions`() = runTest {
            val input = listOf(1)
            val suppressed = IllegalStateException("Suppressed")

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast {
                    val e = RuntimeException("Main")
                    e.addSuppressed(suppressed)
                    throw e
                }
            }

            assertEquals("Main", exception.message)

            // Helper to find suppressed in entire exception chain
            fun findSuppressedInChain(e: Throwable?): List<Throwable> {
                if (e == null) return emptyList()
                return e.suppressed.toList() + findSuppressedInChain(e.cause)
            }

            val allSuppressed = findSuppressedInChain(exception)
            assertTrue(
                allSuppressed.any { it.message == "Suppressed" },
                "Expected suppressed exception with message 'Suppressed' in chain. All suppressed: ${allSuppressed.map { it.message }}"
            )
        }

        @Test
        fun `mapAwaitAllFailFast should handle deeply nested exception cause chain`() = runTest {
            val input = listOf(1)
            val root = IllegalArgumentException("Root")
            val middle = IllegalStateException("Middle", root)
            val top = RuntimeException("Top", middle)

            val exception = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { throw top }
            }

            assertEquals("Top", exception.message)

            // Helper to find message in cause chain
            fun findInCauseChain(e: Throwable?, target: String): Boolean {
                if (e == null) return false
                if (e.message == target) return true
                return findInCauseChain(e.cause, target)
            }

            // Verify the exception chain contains all expected messages
            val chain = generateSequence(exception as Throwable?) { it.cause }.map { it.message }.toList()

            assertTrue(
                findInCauseChain(exception, "Middle"),
                "Expected 'Middle' in exception chain. Chain: $chain"
            )
            assertTrue(
                findInCauseChain(exception, "Root"),
                "Expected 'Root' in exception chain. Chain: $chain"
            )
        }

        @Test
        fun `both methods should preserve stack trace`() = runTest {
            val input = listOf(1)

            val exception1 = assertFailsWith<RuntimeException> {
                input.mapAwaitAllFailFast { throw RuntimeException("Test") }
            }

            val exception2 = assertFailsWith<RuntimeException> {
                input.mapAwaitFirstFailSlow { throw RuntimeException("Test") }
            }

            // Stack traces should contain reference to the test method
            assertTrue(exception1.stackTraceToString().contains("mapAwaitAllFailFast"))
            assertTrue(exception2.stackTraceToString().contains("mapAwaitFirstFailSlow"))
        }
    }
}
