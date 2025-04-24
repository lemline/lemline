// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories.bases

import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxModel
import com.lemline.worker.outbox.OutboxRepository
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for testing outbox repository implementations.
 * This class provides a comprehensive test suite for verifying the behavior of outbox repositories,
 * including message processing, deletion, and concurrent operations.
 *
 * The tests cover:
 * 1. Basic message processing and deletion
 * 2. Message filtering with various parameters (max attempts, cutoff dates)
 * 3. Concurrent operations to ensure thread safety
 * 4. Mixed concurrent operations (processing and deletion)
 *
 * @param T The type of OutboxModel being tested
 */
internal abstract class AbstractOutboxRepositoryTest<T : OutboxModel> {

    /** The repository implementation being tested */
    internal abstract val repository: OutboxRepository<T>

    /** Factory method to create a new instance of the model being tested */
    internal abstract fun createModel(): T

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     */
    @BeforeEach
    @Transactional
    fun setupTest() {
        repository.deleteAll()
    }

    /**
     * Filters a list of messages to find those that are ready to be processed.
     * A message is ready to process if:
     * - It has PENDING status
     * - Its delayedUntil time has passed
     * - It hasn't exceeded maxAttempts
     */
    private fun List<T>.filterToProcess(
        now: Instant = Instant.now(),
        maxAttempts: Int = Int.MAX_VALUE
    ): List<T> =
        filter { it.status == OutBoxStatus.PENDING }
            .filter { it.delayedUntil <= now }
            .filter { it.attemptCount < maxAttempts }

    /**
     * Filters a list of messages to find those that are ready to be deleted.
     * A message is ready to delete if:
     * - It has SENT status
     * - Its delayedUntil time is before the cutoff date
     */
    private fun List<T>.filterToDelete(cutoffDate: Instant = Instant.now()): List<T> =
        filter { it.status == OutBoxStatus.SENT }
            .filter { it.delayedUntil < cutoffDate }

    /**
     * Generates a random non-zero integer for testing purposes.
     * Used to create varied test scenarios with different delay durations.
     */
    private fun randomNonZero(n: Int): Int {
        val r = Random.nextInt(-n, n - 1)
        return if (r >= 0) r + 1 else r
    }

    /**
     * Compares two lists of messages by their IDs.
     * Used to verify that the correct messages are returned by repository operations.
     */
    private fun List<T>.equalTo(other: List<T>): Boolean {
        return this.map { it.id }.toSet() == other.map { it.id }.toSet()
    }

    // Test configuration constants
    private val messageCount = 1000
    private val concurrentRequests = 5
    private val limit = 100
    private val maxAttempts = 3
    private val cutoffDate = Instant.now().minus(7, ChronoUnit.DAYS)

    /**
     * Creates a batch of test messages with randomized properties.
     * Each message has:
     * - Random status (PENDING, SENT, or FAILED)
     * - Random delay duration
     * - Random attempt count
     * - Sequential message content
     */
    @Transactional
    protected fun createMessages(count: Int): List<T> {
        val now = Instant.now()
        val messages = List(count) { i ->
            val duration = randomNonZero(1000).hours.toJavaDuration()
            val status = when (Random.nextInt(0, 2)) {
                0 -> OutBoxStatus.PENDING
                1 -> OutBoxStatus.SENT
                else -> OutBoxStatus.FAILED
            }
            val attemptCount = Random.nextInt(0, 5)

            createModel().apply {
                this.message = "test$i"
                this.status = status
                this.delayedUntil = now.plus(duration)
                this.attemptCount = attemptCount
            }
        }
        repository.persist(messages)
        return messages
    }

    /**
     * Finds and locks messages that are ready to be processed.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are processed and their status is updated.
     */
    @Transactional
    protected open fun findAndLockReadyToProcess(
        limit: Int = Int.MAX_VALUE,
        maxAttempts: Int = Int.MAX_VALUE
    ): List<T> = repository.findAndLockReadyToProcess(limit = limit, maxAttempts = maxAttempts)
        .also { messages ->
            messages.forEach { it.status = OutBoxStatus.FAILED }
            repository.persist(messages)
            repository.flush()
        }

    /**
     * Finds and locks messages that are ready to be deleted.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are deleted after processing.
     */
    @Transactional
    protected open fun findAndLockForDeletion(
        cutoffDate: Instant = Instant.now(),
        limit: Int = Int.MAX_VALUE
    ): List<T> = repository.findAndLockForDeletion(cutoffDate = cutoffDate, limit = limit)
        .also { messages ->
            messages.forEach { it.status = OutBoxStatus.FAILED }
            repository.persist(messages)
            repository.flush()
        }

    /**
     * Tests that findAndLockReadyToProcess returns the correct messages for processing.
     * Verifies that the repository correctly identifies messages that are:
     * - In PENDING status
     * - Past their delay time
     * - Within attempt limits
     */
    @Test
    fun `findAndLockReadyToProcess should return all eligible pending messages that are ready for processing`() {
        val messages = createMessages(messageCount)
        val expected = findAndLockReadyToProcess()
        val actual = messages.filterToProcess()
        println("expected for processing: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findAndLockReadyToProcess respects the maxAttempts parameter.
     * Verifies that messages exceeding the attempt limit are not returned.
     */
    @Test
    fun `findAndLockReadyToProcess should exclude messages that have exceeded maxAttempts`() {
        val messages = createMessages(messageCount)
        val expected = findAndLockReadyToProcess(maxAttempts = maxAttempts)
        val actual = messages.filterToProcess(maxAttempts = maxAttempts)
        println("expected for processing with maxAttempts: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findAndLockReadyToProcess respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for processing.
     */
    @Test
    fun `findAndLockReadyToProcess should respect the limit parameter and only return valid candidates`() {
        val messages = createMessages(messageCount)
        val actual = findAndLockReadyToProcess(limit = limit)
        val expected = messages.filterToProcess()

        actual.size shouldBeLessThanOrEqualTo limit
        val expectedIds = expected.map { it.id }
        actual.filter { it.id !in expectedIds }.size shouldBe 0
    }

    /**
     * Tests that findAndLockForDeletion returns the correct messages for deletion.
     * Verifies that the repository correctly identifies messages that are:
     * - In SENT status
     * - Past their cutoff date
     */
    @Test
    fun `findAndLockForDeletion should return all sent messages that are past their cutoff date`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToDelete()
        val actual = findAndLockForDeletion()
        println("expected for deletion: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findAndLockForDeletion respects the cutoffDate parameter.
     * Verifies that only messages older than the cutoff date are returned.
     */
    @Test
    fun `findAndLockForDeletion should only return messages older than the specified cutoff date`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToDelete(cutoffDate = cutoffDate)
        val actual = findAndLockForDeletion(cutoffDate = cutoffDate)
        println("expected for deletion with cutoffDate: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findAndLockForDeletion respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for deletion.
     */
    @Test
    fun `findAndLockForDeletion should respect the limit parameter and only return valid candidates`() {
        val messages = createMessages(messageCount)
        val actual = findAndLockForDeletion(limit = limit)
        val expected = messages.filterToDelete()

        actual.size shouldBeLessThanOrEqualTo limit
        val expectedIds = expected.map { it.id }
        actual.filter { it.id !in expectedIds }.size shouldBe 0
    }

    /**
     * Tests concurrent message processing to ensure thread safety.
     * Verifies that:
     * - No message is processed more than once
     * - All eligible messages are eventually processed
     * - The operation completes within a reasonable time
     */
    @Test
    fun `findAndLockReadyToProcess should handle concurrent requests without duplicate processing`() {
        val messages = createMessages(messageCount)
        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        println("expected processedMessages: $expectedProcessed")

        val processedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        val results = findAndLockReadyToProcess(limit = limit, maxAttempts = maxAttempts)
                        synchronized(processedMessages) { processedMessages.addAll(results) }
                    } catch (e: Exception) {
                        println(e.printStackTrace())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
        } while (processedMessages.size != expectedProcessed && Instant.now().isBefore(timeout))
        executor.shutdown()

        processedMessages.size shouldBe expectedProcessed
        val processIds = processedMessages.map { it.id }
        processIds.toSet().size shouldBe processIds.size
    }

    /**
     * Tests concurrent message deletion to ensure thread safety.
     * Verifies that:
     * - No message is deleted more than once
     * - All eligible messages are eventually deleted
     * - The operation completes within a reasonable time
     */
    @Test
    fun `findAndLockForDeletion should handle concurrent requests without duplicate deletion`() {
        val messages = createMessages(messageCount)
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        println("expected deletedMessages: $expectedDeleted")

        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        val results = findAndLockForDeletion(cutoffDate = cutoffDate, limit = limit)
                        synchronized(deletedMessages) { deletedMessages.addAll(results) }
                    } catch (e: Exception) {
                        println(e.printStackTrace())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
        } while (deletedMessages.size != expectedDeleted && Instant.now().isBefore(timeout))
        executor.shutdown()

        deletedMessages.size shouldBe expectedDeleted
        val processIds = deletedMessages.map { it.id }
        processIds.toSet().size shouldBe processIds.size
    }

    /**
     * Tests mixed concurrent operations (processing and deletion) to ensure thread safety.
     * Verifies that:
     * - No message is processed or deleted more than once
     * - All eligible messages are eventually processed or deleted
     * - No message is both processed and deleted
     * - The operation completes within a reasonable time
     */
    @Test
    fun `should handle mixed concurrent processing and deletion operations without conflicts`() {
        val messages = createMessages(messageCount)
        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        println("expected processedMessages: $expectedProcessed")
        println("expected deletedMessages: $expectedDeleted")

        val processedMessages = mutableListOf<T>()
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        when (Random.nextInt(0, 2)) {
                            0 -> {
                                val results = findAndLockReadyToProcess(limit = limit, maxAttempts = maxAttempts)
                                synchronized(processedMessages) { processedMessages.addAll(results) }
                            }

                            1 -> {
                                val results = findAndLockForDeletion(cutoffDate, limit = limit)
                                synchronized(deletedMessages) { deletedMessages.addAll(results) }
                            }
                        }
                    } catch (e: Exception) {
                        println(e.printStackTrace())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
            println("processedMessages: ${processedMessages.size} $expectedProcessed")
            println("deletedMessages: ${deletedMessages.size} $expectedDeleted")
        } while ((processedMessages.size != expectedProcessed || deletedMessages.size != expectedDeleted) &&
            Instant.now().isBefore(timeout)
        )
        executor.shutdown()

        processedMessages.size shouldBe expectedProcessed
        deletedMessages.size shouldBe expectedDeleted
        val deletedIds = deletedMessages.map { it.id }
        deletedIds.toSet().size shouldBe deletedIds.size
        val processedIds = processedMessages.map { it.id }
        processedIds.toSet().size shouldBe processedIds.size

        Assertions.assertTrue(
            processedIds.toSet().intersect(deletedIds.toSet()).isEmpty(),
            "No message should be both processed and deleted",
        )
    }
}
