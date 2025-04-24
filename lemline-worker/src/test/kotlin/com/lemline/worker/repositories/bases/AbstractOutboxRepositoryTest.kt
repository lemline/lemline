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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test


/**
 * Abstract base class for retry repository tests.
 */
internal abstract class AbstractOutboxRepositoryTest<T : OutboxModel> {

    internal abstract val repository: OutboxRepository<T>

    internal abstract fun createModel(): T

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        repository.deleteAll()
    }

    private fun List<T>.filterToProcess(
        now: Instant = Instant.now(),
        maxAttempts: Int = Int.MAX_VALUE
    ): List<T> =
        filter { it.status == OutBoxStatus.PENDING }
            .filter { it.delayedUntil <= now }
            .filter { it.attemptCount < maxAttempts }

    private fun List<T>.filterToDelete(cutoffDate: Instant = Instant.now()): List<T> =
        filter { it.status == OutBoxStatus.SENT }
            .filter { it.delayedUntil < cutoffDate }

    /**
     * Generates a random non-zero integer between -n and n.
     */
    private fun randomNonZero(n: Int): Int {
        val r = Random.nextInt(-n, n - 1)
        return if (r >= 0) r + 1 else r
    }

    private fun List<T>.equalTo(other: List<T>): Boolean {
        return this.map { it.id }.toSet() == other.map { it.id }.toSet()
    }

    private val messageCount = 1000
    private val concurrentRequests = 5
    private val limit = 100
    private val maxAttempts = 3
    private val cutoffDate = Instant.now().minus(7, ChronoUnit.DAYS)

    /**
     * Creates a list of test messages with random data.
     */
    @Transactional
    protected fun createMessages(count: Int): List<T> {
        val now = Instant.now()
        // Create test messages in a transaction
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
     * Finds and locks messages ready to process.
     * Updates the status of the messages to FAILED and persists them for not to be processed again.
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
     * Finds and locks messages for deletion.
     * Updates the status of the messages to FAILED and persists them for not to be processed again.
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

    @Test
    fun `findAndLockReadyToProcess should return messages ready to process`() {
        val messages = createMessages(messageCount)

        // test request
        val expected = findAndLockReadyToProcess()
        val actual = messages.filterToProcess()
        println("expected for processing: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    @Test
    fun `findAndLockReadyToProcess should return messages ready to process with maxAttempts`() {
        val messages = createMessages(messageCount)

        // test request with max attempts
        val expected = findAndLockReadyToProcess(maxAttempts = maxAttempts)
        val actual = messages.filterToProcess(maxAttempts = maxAttempts)
        println("expected for processing with maxAttempts: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    @Test
    fun `findAndLockReadyToProcess should return messages ready to process with limit`() {
        val messages = createMessages(messageCount)

        // test request with limit
        val actual = findAndLockReadyToProcess(limit = limit)
        val expected = messages.filterToProcess()

        actual.size shouldBeLessThanOrEqualTo limit
        val expectedIds = expected.map { it.id }
        actual.filter { it.id !in expectedIds }.size shouldBe 0
    }


    @Test
    fun `findAndLockForDeletion should return messages ready for deletion`() {
        val messages = createMessages(messageCount)

        // test request
        val expected = messages.filterToDelete()
        val actual = findAndLockForDeletion()
        println("expected for deletion: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    @Test
    fun `findAndLockForDeletion should return messages ready for deletion with cutoffDate`() {
        val messages = createMessages(messageCount)

        // test request with cutoffDate
        val expected = messages.filterToDelete(cutoffDate = cutoffDate)
        val actual = findAndLockForDeletion(cutoffDate = cutoffDate)
        println("expected for deletion with cutoffDate: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    @Test
    fun `findAndLockForDeletion should return messages ready for deletion with Limit`() {
        val messages = createMessages(messageCount)

        // test request with limit
        val actual = findAndLockForDeletion(limit = limit)
        val expected = messages.filterToDelete()

        // results should be limited to the limit
        actual.size shouldBeLessThanOrEqualTo limit

        // results should be part of the expected
        val expectedIds = expected.map { it.id }
        actual.filter { it.id !in expectedIds }.size shouldBe 0
    }

    @Test
    fun `findAndLockReadyToProcess should not return same messages to concurrent requests`() {
        val messages = createMessages(messageCount)

        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        println("expected processedMessages: $expectedProcessed")

        // Submit concurrent requests
        val processedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS) // Set a 5-second timeout
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

        // Verify the results
        processedMessages.size shouldBe expectedProcessed
        // Verify there is no duplicate
        val processIds = processedMessages.map { it.id }
        processIds.toSet().size shouldBe processIds.size
    }

    @Test
    fun `findAndLockForDeletion should handle concurrent deletion requests`() = runTest {
        // Create test messages
        val messages = createMessages(messageCount)

        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        println("expected deletedMessages: $expectedDeleted")

        // Submit concurrent requests
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS) // Set a 5-second timeout
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

        // Verify the results
        deletedMessages.size shouldBe expectedDeleted
        // Verify there is no duplicate
        val processIds = deletedMessages.map { it.id }
        processIds.toSet().size shouldBe processIds.size
    }

    @Test
    fun `should handle mixed concurrent operations`() {
        // Create test messages
        val messages = createMessages(messageCount)

        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        println("expected processedMessages: $expectedProcessed")
        println("expected deletedMessages: $expectedDeleted")

        // When
        val processedMessages = mutableListOf<T>()
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        // Submit concurrent requests until all messages are processed and deleted
        val timeout = Instant.now().plus(5, ChronoUnit.SECONDS) // Set a 5-second timeout
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        when (Random.nextInt(0, 2)) {
                            // Process messages
                            0 -> {
                                val results = findAndLockReadyToProcess(limit = limit, maxAttempts = maxAttempts)
                                synchronized(processedMessages) { processedMessages.addAll(results) }
                            }
                            // Delete messages
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

        // Verify the results
        processedMessages.size shouldBe expectedProcessed
        deletedMessages.size shouldBe expectedDeleted
        // Verify there is no duplicate
        val deletedIds = deletedMessages.map { it.id }
        deletedIds.toSet().size shouldBe deletedIds.size
        // Verify there is no duplicate
        val processedIds = processedMessages.map { it.id }
        processedIds.toSet().size shouldBe processedIds.size

        // Verify no overlap between processed and deleted messages
        Assertions.assertTrue(
            processedIds.toSet().intersect(deletedIds.toSet()).isEmpty(),
            "No message should be both processed and deleted",
        )
    }
}
