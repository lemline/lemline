// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.OutboxRepository
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
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
    internal abstract fun createModel(message: String = "test"): T

    /** Method to create a new instance of the model being tested */
    internal abstract fun copyModel(model: T, message: String): T

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     */
    @BeforeEach
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

            createModel("test$i").apply {
                this.status = status
                this.delayedUntil = now.plus(duration)
                this.attemptCount = attemptCount
            }
        }
        repository.upsert(messages)
        return messages
    }

    /**
     * Finds and locks messages that are ready to be processed.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are processed and their status is updated.
     */
    protected open fun findMessagesToProcess(
        limit: Int = Int.MAX_VALUE,
        maxAttempts: Int = Int.MAX_VALUE
    ): List<T> = repository.findMessagesToProcess(limit = limit, maxAttempts = maxAttempts)
        .also { messages ->
            if (messages.isNotEmpty()) {
                messages.forEach { it.status = OutBoxStatus.FAILED }
                repository.upsert(messages)
            }
        }

    /**
     * Finds and locks messages that are ready to be deleted.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are deleted after processing.
     */
    protected open fun findMessagesToDelete(
        cutoffDate: Instant = Instant.now(),
        limit: Int = Int.MAX_VALUE
    ): List<T> = repository.findMessagesToDelete(cutoffDate = cutoffDate, limit = limit)
        .also { messages ->
            if (messages.isNotEmpty()) {
                messages.forEach { it.status = OutBoxStatus.FAILED }
                repository.upsert(messages)
            }
        }

    /**
     * Tests that findMessagesToProcess returns the correct messages for processing.
     * Verifies that the repository correctly identifies messages that are:
     * - In PENDING status
     * - Past their delay time
     * - Within attempt limits
     */
    @Test
    fun `findMessagesToProcess should return all eligible pending messages that are ready for processing`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToProcess()
        val actual = findMessagesToProcess()
        println("expected for processing: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the maxAttempts parameter.
     * Verifies that messages exceeding the attempt limit are not returned.
     */
    @Test
    fun `findMessagesToProcess should exclude messages that have exceeded maxAttempts`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToProcess(maxAttempts = maxAttempts)
        val actual = findMessagesToProcess(maxAttempts = maxAttempts)
        println("expected for processing with maxAttempts: ${expected.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for processing.
     */
    @Test
    fun `findMessagesToProcess should respect the limit parameter and only return valid candidates`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToProcess()
        val actual = findMessagesToProcess(limit = limit)

        actual.size shouldBeLessThanOrEqualTo limit
        val expectedIds = expected.map { it.id }
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
        val messages = createMessages(messageCount)
        val expected = messages.filterToDelete()
        val actual = findMessagesToDelete()
        println("expected for deletion: ${expected.size}")
        println("      actual deletion: ${actual.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the cutoffDate parameter.
     * Verifies that only messages older than the cutoff date are returned.
     */
    @Test
    fun `findMessagesToDelete should only return messages older than the specified cutoff date`() {
        val messages = createMessages(messageCount)
        val expected = messages.filterToDelete(cutoffDate = cutoffDate)
        val actual = findMessagesToDelete(cutoffDate = cutoffDate)
        println("expected for deletion with cutoffDate: ${expected.size}")
        println("      actual deletion with cutoffDate: ${actual.size}")
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for deletion.
     */
    @Test
    fun `findMessagesToDelete should respect the limit parameter and only return valid candidates`() {
        val messages = createMessages(messageCount)
        val actual = findMessagesToDelete(limit = limit)
        val expected = messages.filterToDelete()

        actual.size shouldBeLessThanOrEqualTo limit
        val expectedIds = expected.map { it.id }
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
                        val results = findMessagesToProcess(limit = limit, maxAttempts = maxAttempts)
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
                        val results = findMessagesToDelete(cutoffDate = cutoffDate, limit = limit)
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
        val messages = createMessages(messageCount)
        val expectedProcessed = messages.filterToProcess(maxAttempts = maxAttempts).size
        val expectedDeleted = messages.filterToDelete(cutoffDate).size
        println("expected processedMessages: $expectedProcessed")
        println("expected deletedMessages: $expectedDeleted")

        val processedMessages = mutableListOf<T>()
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Instant.now().plus(1, ChronoUnit.SECONDS)
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        when (Random.nextInt(0, 2)) {
                            0 -> {
                                val results = findMessagesToProcess(limit = limit, maxAttempts = maxAttempts)
                                synchronized(processedMessages) { processedMessages.addAll(results) }
                            }

                            1 -> {
                                val results = findMessagesToDelete(cutoffDate, limit = limit)
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
        val messages = List(10) { createModel("test") }
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
                    repository.upsert(messages.subList(start, end))
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
     * Tests concurrent transaction handling.
     * Verifies that the repository properly handles concurrent transactions.
     */
    @Test
    fun `should handle concurrent transactions correctly`() {
        // Given
        val message = createModel().apply {
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
                    repository.upsert(message)
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
     * Tests that count() returns the correct number of records.
     * Verifies that the count matches the number of persisted messages.
     */
    @Test
    fun `count should return the correct number of records`() {
        // Given
        val messages = createMessages(10)

        // When
        val count = repository.count()

        // Then
        count shouldBe messages.size.toLong()
    }

    /**
     * Tests that count() returns 0 for an empty table.
     * Verifies that the count is correct when no messages exist.
     */
    @Test
    fun `count should return 0 for an empty table`() {
        // When
        val count = repository.count()

        // Then
        count shouldBe 0L
    }

    /**
     * Tests that count() is consistent with listAll().
     * Verifies that the count matches the number of records returned by listAll.
     */
    @Test
    fun `count should be consistent with listAll`() {
        // Given
        val messages = createMessages(5)

        // When
        val count = repository.count()
        val allMessages = repository.listAll()

        // Then
        count shouldBe allMessages.size.toLong()
    }

    /**
     * Tests that count() is accurate after deletions.
     * Verifies that the count decreases when messages are deleted.
     */
    @Test
    fun `count should be accurate after deletions`() {
        // Given
        val messages = createMessages(10)
        val messagesToDelete = messages.take(3)

        // When
        repository.delete(messagesToDelete)
        val count = repository.count()

        // Then
        count shouldBe (messages.size - messagesToDelete.size).toLong()
    }

    /**
     * Tests that count() is accurate after updates.
     * Verifies that the count remains the same when messages are updated.
     */
    @Test
    fun `count should be accurate after updates`() {
        // Given
        val messages = createMessages(5)
        repository.upsert(messages)

        // update by changing the status of all messages
        messages.forEach { it.status = OutBoxStatus.PENDING }

        // When
        repository.upsert(messages)
        val count = repository.count()

        // Then
        count shouldBe messages.size.toLong()
    }

    // --- Tests for persist(entity, force) ---

    @Test
    fun `persist single entity with force=true should insert new`() {
        val newEntity = createModel("msg-new-force-true")
        shouldNotThrow<Exception> { repository.upsert(newEntity) }
        val retrieved = repository.findById(newEntity.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe newEntity.message
    }

    @Test
    fun `persist single entity with force=true should update existing`() {
        val existingEntity = createAndPersistOutboxEntity("msg-update-force-true") // Persist first
        val updatedEntity = copyModel(existingEntity, message = "Updated Message")

        shouldNotThrow<Exception> { repository.upsert(updatedEntity) }
        val retrieved = repository.findById(existingEntity.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe "Updated Message"
        repository.count() shouldBe 1L // Ensure count hasn't increased
    }

    @Test
    fun `persist single entity with force=false should insert new`() {
        val newEntity = createModel("msg-new-force-false")
        shouldNotThrow<Exception> { repository.insert(newEntity) }
        val retrieved = repository.findById(newEntity.id)
        retrieved shouldNotBe null
        retrieved?.message shouldBe newEntity.message
    }

    @Test
    fun `persist single entity with force=false should fail on existing`() {
        val existingEntity = createAndPersistOutboxEntity("msg-fail-force-false") // Persist first
        // Attempt to persist the same entity again with force=false
        shouldThrow<SQLException> {
            repository.insert(existingEntity)
        }
        // Verify original data is untouched and count is still 1
        val retrieved = repository.findById(existingEntity.id)
        retrieved?.message shouldBe existingEntity.message
        repository.count() shouldBe 1L // Count should remain 1 after failed insert
    }

    // --- Tests for persist(entities, force) ---

    @Test
    fun `persist list with force=true should insert new and update existing`() {
        val existingEntity = createAndPersistOutboxEntity("batch-existing-force-true")
        val updatedEntity = copyModel(existingEntity, message = "Batch Updated")
        val newEntity1 = createModel("batch-new1-force-true")
        val newEntity2 = createModel("batch-new2-force-true")

        val entitiesToPersist = listOf(updatedEntity, newEntity1, newEntity2)

        shouldNotThrow<Exception> { repository.upsert(entitiesToPersist) }

        // Verify update
        val retrievedUpdated = repository.findById(existingEntity.id)
        retrievedUpdated?.message shouldBe "Batch Updated"

        // Verify inserts
        repository.findById(newEntity1.id) shouldNotBe null
        repository.findById(newEntity2.id) shouldNotBe null

        // Verify count
        repository.count() shouldBe 3L
    }

//    @Test
//    fun `persist list with force=false should insert new only`() {
//        val newEntity1 = createModel("batch-new1-force-false")
//        val newEntity2 = createModel("batch-new2-force-false")
//        val entitiesToPersist = listOf(newEntity1, newEntity2)
//
//        shouldNotThrow<Exception> { repository.insert(entitiesToPersist) }
//
//        repository.findById(newEntity1.id) shouldNotBe null
//        repository.findById(newEntity2.id) shouldNotBe null
//        repository.count() shouldBe 2L
//    }
//
//    @Test
//    fun `persist list with force=false should fail if any existing`() {
//        val existingEntity = createAndPersistOutboxEntity("batch-fail-force-false")
//        val newEntity1 = createModel("batch-fail-new1-force-false")
//        val entitiesToPersist = listOf(existingEntity, newEntity1) // Contains duplicate
//
//        shouldThrow<SQLException> {
//            repository.insert(entitiesToPersist)
//        }
//
//        // Verify state: original entity might still be there, new one might not be (depends on batch behavior)
//        // It's safer to just check the count didn't increase incorrectly
//        repository.count() shouldBe 1L
//    }

    // --- Helper to persist an entity for setup ---
    private fun createAndPersistOutboxEntity(message: String): T {
        val entity = createModel(message)
        repository.upsert(entity) // Use force=true for setup to ensure it exists
        return entity
    }
}
