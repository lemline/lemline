// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutBoxStatus.SENT
import com.lemline.runner.repositories.OutboxRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
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
internal abstract class OutboxRepositoryTest<T : OutboxModel> {

    /** The repository implementation being tested */
    internal abstract val repository: OutboxRepository<T>

    /** Method to create a new instance of the model being tested */
    internal abstract fun createWithMessage(message: String = "test"): T

    /** Method to create a new instance of the model being tested */
    internal abstract fun copyModel(model: T, message: String): T

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     */
    @BeforeEach
    fun setupTest() = runTest {
        repository.deleteAll()
        delay(100)
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
     * Finds and locks messages that are ready to be processed.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are processed and their status is updated.
     */
    protected open fun findMessagesToProcess(
        maxAttempts: Int = Int.MAX_VALUE,
        limit: Int = Int.MAX_VALUE,
        connection: Connection? = null
    ): List<T> = repository.findMessagesToProcess(maxAttempts = maxAttempts, limit = limit, connection)

    /**
     * Finds and locks messages that are ready to be deleted.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are deleted after processing.
     */
    protected open fun findMessagesToDelete(
        cutoffDate: Instant = Instant.now(),
        limit: Int = Int.MAX_VALUE,
        connection: Connection? = null
    ): List<T> = repository.findMessagesToDelete(cutoffDate = cutoffDate, limit = limit, connection)

    /**
     * Tests that findMessagesToProcess returns the correct messages for processing.
     * Verifies that the repository correctly identifies messages that are:
     * - In PENDING status
     * - Past their delay time
     * - Within attempt limits
     */
    @Test
    fun `findMessagesToProcess should return all eligible pending messages that are ready for processing`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToProcess()
        val expectedIds = expected.map { it.id }
        val actual = findMessagesToProcess().filter { it.id in messagesIds }

        println("expected for processing: ${expected.size}")
        println("  actual for processing: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the maxAttempts parameter.
     * Verifies that messages exceeding the attempt limit are not returned.
     */
    @Test
    fun `findMessagesToProcess should exclude messages that have exceeded maxAttempts`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToProcess(maxAttempts = maxAttempts)
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToProcess(maxAttempts = maxAttempts).filter { it.id in messagesIds }

        println("expected for processing with maxAttempts: ${expected.size}")
        println("  actual for processing with maxAttempts: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for processing.
     */
    @Test
    fun `findMessagesToProcess should respect the limit parameter and only return valid candidates`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToProcess()
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToProcess(limit = limit).filter { it.id in messagesIds }

        actual.size shouldBeLessThanOrEqualTo limit

        println("expected for processing with limit: ${expected.size}")
        println("  actual for processing with limit: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        actual.count { it.id !in expectedIds } shouldBe 0
    }

    /**
     * Tests that findMessagesToDelete returns the correct messages for deletion.
     * Verifies that the repository correctly identifies messages that are:
     * - In SENT status
     * - Past their cutoff date
     */
    @Test
    fun `findMessagesToDelete should return all sent messages that are past their cutoff date`() {
        val now = Instant.now()
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToDelete(cutoffDate = now)
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToDelete(cutoffDate = now).filter { it.id in messagesIds }

        println("expected for deletion: ${expected.size}")
        println("      actual deletion: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the cutoffDate parameter.
     * Verifies that only messages older than the cutoff date are returned.
     */
    @Test
    fun `findMessagesToDelete should only return messages older than the specified cutoff date`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToDelete(cutoffDate = cutoffDate)
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToDelete(cutoffDate = cutoffDate).filter { it.id in messagesIds }

        println("expected for deletion with cutoffDate: ${expected.size}")
        println("      actual deletion with cutoffDate: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for deletion.
     */
    @Test
    fun `findMessagesToDelete should respect the limit parameter and only return valid candidates`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.filterToDelete()
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToDelete(limit = limit).filter { it.id in messagesIds }

        actual.size shouldBeLessThanOrEqualTo limit

        println("expected for deletion with limit: ${expected.size}")
        println("      actual deletion with limit: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        actual.count { it.id !in expectedIds } shouldBe 0
    }

    /**
     * Tests concurrent message processing to ensure thread safety.
     * Verifies that:
     * - No message is processed more than once
     * - All eligible messages are eventually processed
     * - The operation completes within a reasonable time
     */
    @Test
    fun `findMessagesToProcess should handle concurrent requests without duplicate processing`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        val processedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(1, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        // run inside a transaction
                        repository.withTransaction { connection ->
                            // get messages to process
                            val results = findMessagesToProcess(maxAttempts, limit, connection)
                            // mark messages as sent
                            results.forEach { it.status = SENT }
                            // save them
                            repository.update(results, connection)
                            // record processed messages
                            synchronized(processedMessages) { processedMessages.addAll(results.filter { it.id in messagesIds }) }
                        }
                    } catch (e: Exception) {
                        println(e.printStackTrace())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
            println("processedMessages: ${processedMessages.size} / $expectedProcessed")
        } while (processedMessages.size < expectedProcessed && Instant.now().isBefore(timeout))
        executor.shutdown()

        processedMessages shouldHaveSize expectedProcessed
        val processIds = processedMessages.map { it.id }
        processIds.toSet() shouldHaveSize processIds.size
    }

    /**
     * Tests concurrent message deletion to ensure thread safety.
     * Verifies that:
     * - No message is deleted more than once
     * - All eligible messages are eventually deleted
     * - The operation completes within a reasonable time
     */
    @Test
    fun `findMessagesToDelete should handle concurrent requests without duplicate deletion`() {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(1, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        // run inside a transaction
                        repository.withTransaction { connection ->
                            // get messages to delete
                            val results = findMessagesToDelete(cutoffDate, limit, connection)
                            // delete them
                            repository.delete(results, connection)
                            // record deleted messages
                            synchronized(deletedMessages) { deletedMessages.addAll(results.filter { it.id in messagesIds }) }
                        }
                    } catch (e: Exception) {
                        println(e.printStackTrace())
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
            println("deletedMessages: ${deletedMessages.size} / $expectedDeleted")
        } while (deletedMessages.size < expectedDeleted && Instant.now().isBefore(timeout))
        executor.shutdown()

        deletedMessages shouldHaveSize expectedDeleted
        val processIds = deletedMessages.map { it.id }
        processIds.toSet() shouldHaveSize processIds.size
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
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.id }
        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        val processedMessages = mutableListOf<T>()
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(1, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        // run inside a transaction
                        repository.withTransaction { connection ->
                            when (Random.nextInt(0, 2)) {
                                0 -> {
                                    println("Processing...")
                                    // get messages to process
                                    val results = findMessagesToProcess(maxAttempts, limit, connection)
                                    // mark messages as sent
                                    results.forEach { it.status = SENT }
                                    // delete them
                                    repository.delete(results, connection)
                                    // record processed messages
                                    synchronized(processedMessages) { processedMessages.addAll(results.filter { it.id in messagesIds }) }
                                }

                                1 -> {
                                    println("Deleting...")
                                    // get messages to delete
                                    val results = findMessagesToDelete(cutoffDate, limit, connection)
                                    // delete them
                                    repository.delete(results, connection)
                                    // record deleted messages
                                    synchronized(deletedMessages) { deletedMessages.addAll(results.filter { it.id in messagesIds }) }
                                }

                                else -> {}
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
            println("processedMessages: ${processedMessages.size} / $expectedProcessed")
            println("deletedMessages: ${deletedMessages.size} / $expectedDeleted")
        } while ((processedMessages.size < expectedProcessed || deletedMessages.size < expectedDeleted) &&
            Instant.now().isBefore(timeout)
        )
        executor.shutdown()

        processedMessages shouldHaveSize expectedProcessed
        deletedMessages shouldHaveSize expectedDeleted
        val deletedIds = deletedMessages.map { it.id }
        deletedIds.toSet() shouldHaveSize deletedIds.size
        val processedIds = processedMessages.map { it.id }
        processedIds.toSet() shouldHaveSize processedIds.size

        processedIds.intersect(deletedIds.toSet()).shouldBeEmpty()
    }

    /**
     * Tests that createModel correctly maps ResultSet data to model properties.
     * Verifies that all fields are properly mapped and converted.
     */
    @Test
    fun `createModel should correctly map ResultSet data to model properties`() {
        // Given
        val expectedId = "test-id"
        val expectedMessage = "test message"
        val expectedStatus = OutBoxStatus.PENDING
        val expectedDelayedUntil = Instant.now().truncatedTo(ChronoUnit.MILLIS) // Truncate for DB precision
        val expectedAttemptCount = 2
        val expectedLastError = "test error"

        val mockResultSet = mockk<ResultSet> {
            every { getString("id") } returns expectedId
            every { getString("message") } returns expectedMessage
            every { getString("status") } returns expectedStatus.name
            every { getTimestamp("delayed_until") } returns Timestamp.from(expectedDelayedUntil)
            every { getInt("attempt_count") } returns expectedAttemptCount
            every { getString("last_error") } returns expectedLastError
        }

        // When
        val model = repository.createModel(mockResultSet)

        // Then
        model.id shouldBe expectedId
        model.message shouldBe expectedMessage
        model.status shouldBe expectedStatus
        model.delayedUntil shouldBe expectedDelayedUntil
        model.attemptCount shouldBe expectedAttemptCount
        model.lastError shouldBe expectedLastError
    }

    /**
     * Tests that the repository handles concurrent access correctly.
     * Verifies that concurrent operations don't corrupt the data.
     */
    @Test
    fun `should handle concurrent access correctly`() {
        // Given
        val messages = List(10) { createWithMessage("test") }
        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        // When
        repeat(nThreads) { threadIndex ->
            executor.submit {
                try {
                    val start = threadIndex * 2
                    val end = start + 2
                    repository.insert(messages.subList(start, end))
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        executor.shutdown()

        // Then
        exceptions.shouldBeEmpty()
        val allMessages = repository.listAll()
        allMessages shouldHaveSize messages.size
        allMessages.map { it.id }.toSet() shouldHaveSize messages.size
    }

    /**
     * Verifies that the repository properly handles concurrent inserts.
     */
    @Test
    fun `insert should handle concurrent transactions correctly`() {
        // Given
        val message = createWithMessage().apply {
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now()
            attemptCount = 0
        }

        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        // When
        repeat(nThreads) {
            executor.submit {
                try {
                    repository.insert(message)
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        executor.shutdown()

        // Then
        exceptions.shouldBeEmpty()
        val persistedMessage = repository.findById(message.id)
        persistedMessage shouldNotBe null
    }

    /**
     * Verifies that the repository properly handles concurrent updates.
     */
    @Test
    fun `update should handle concurrent transactions correctly`() {
        // Given
        val original = insertWithMessage("original")
        val updated = copyModel(original, message = "updated")

        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        // When
        repeat(nThreads) {
            executor.submit {
                try {
                    repository.update(updated)
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        executor.shutdown()

        // Then
        exceptions.shouldBeEmpty()
        val persistedMessage = repository.findById(original.id)
        persistedMessage?.message shouldBe "updated"
    }

    /**
     * Verifies that the count is incremented after messages insertion
     */
    @Test
    fun `count should return 0 for an empty table`() {
        // Given
        val count = repository.count()

        // When
        insertMessages(10)

        // Then
        repository.count() shouldBe count + 10
    }

    /**
     * Tests that count() is consistent with listAll().
     * Verifies that the count matches the number of records returned by listAll.
     */
    @Test
    fun `count should be consistent with listAll`() {
        // Given
        insertMessages(10)

        // When
        val count = repository.count()
        val allMessages = repository.listAll()

        // Then
        count shouldBe allMessages.size.toLong()
    }

    /**
     * Tests messages deletion
     */
    @Test
    fun `batch delete should successful`() {
        // Given
        val messages = insertMessages(10)
        val messagesToDelete = messages.take(5)

        // When
        repository.delete(messagesToDelete)

        // Then
        messages.forEachIndexed { index, message ->
            if (index < 5)
                repository.findById(message.id) shouldBe null
            else
                repository.findById(message.id) shouldNotBe null
        }
    }

    /**
     * Tests messages creation.
     */
    @Test
    fun `batch updates should successful`() {
        // Given
        val messages = insertMessages(10)
        val messagesToUpdate = messages.take(5)
        repository.insert(messages)

        // update by changing the status of all messages
        messagesToUpdate.forEach { it.attemptCount = 100 }

        // When
        repository.update(messagesToUpdate) shouldBe 5

        // Then
        messages.forEachIndexed { index, message ->
            if (index < 5)
                repository.findById(message.id)!!.attemptCount shouldBe 100
            else
                repository.findById(message.id)!!.attemptCount shouldBeLessThan 100
        }
    }

    // --- Tests for persist(entity, force) ---

    @Test
    fun `insert new entity should be successful`() {
        val original = createWithMessage("original")

        repository.insert(original) shouldBe 1

        val retrieved = repository.findById(original.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe original.message
    }

    @Test
    fun `insert existing entity should fail`() {
        val original = insertWithMessage("original")
        val updated = copyModel(original, message = "updated")

        repository.insert(updated) shouldBe 0

        val retrieved = repository.findById(original.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe original.message
    }

    @Test
    fun `update existing entity should be successful`() {
        val original = insertWithMessage("original")
        val updated = copyModel(original, message = "updated")

        repository.update(updated) shouldBe 1

        val retrieved = repository.findById(original.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe "updated"
    }

    @Test
    fun `update new entity should fail`() {
        val original = createWithMessage("original")

        repository.update(original) shouldBe 0

        val retrieved = repository.findById(original.id)
        retrieved shouldBe null
    }

    // --- Tests for persist(entities, force) ---

    @Test
    fun `update list should update only existing`() {
        val original = insertWithMessage("original-0")
        val updated = copyModel(original, message = "updated")
        val newEntity1 = createWithMessage("original-1")
        val newEntity2 = createWithMessage("original-2")

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.update(entitiesToPersist) shouldBe 1

        // Verify updates
        val retrievedUpdated = repository.findById(original.id)
        retrievedUpdated?.message shouldBe "updated"

        // Verify inserts
        repository.findById(newEntity1.id) shouldBe null
        repository.findById(newEntity2.id) shouldBe null
    }

    @Test
    fun `insert list should insert only non-existing`() {
        val original = insertWithMessage("original-0")
        val updated = copyModel(original, message = "updated")
        val newEntity1 = createWithMessage("original-1")
        val newEntity2 = createWithMessage("original-2")

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.insert(entitiesToPersist) shouldBe 2

        // Verify updates
        val retrievedUpdated = repository.findById(original.id)
        retrievedUpdated?.message shouldBe "original-0"

        // Verify inserts
        repository.findById(newEntity1.id) shouldNotBe null
        repository.findById(newEntity2.id) shouldNotBe null
    }


    /**
     * Creates a batch of test messages with randomized properties.
     * Each message has:
     * - Random status (PENDING, SENT, or FAILED)
     * - Random delay duration
     * - Random attempt count
     * - Sequential message content
     */
    private fun insertMessages(count: Int): List<T> {
        val now = Instant.now()
        val messages = List(count) { i ->
            val duration = randomNonZero(1000).hours.toJavaDuration()
            val status = when (Random.nextInt(0, 2)) {
                0 -> OutBoxStatus.PENDING
                1 -> OutBoxStatus.SENT
                else -> OutBoxStatus.FAILED
            }
            val attemptCount = Random.nextInt(0, 5)

            createWithMessage("test$i").apply {
                this.status = status
                this.delayedUntil = now.plus(duration)
                this.attemptCount = attemptCount
            }
        }
        repository.insert(messages)
        return messages
    }

    // --- Helper to persist an entity for setup ---
    private fun insertWithMessage(message: String): T {
        val entity = createWithMessage(message)
        repository.insert(entity)
        return entity
    }
}
