// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.FAILED
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import com.lemline.runner.repositories.OutboxRepository
import io.kotest.common.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
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
@ExperimentalTime
internal abstract class OutboxRepositoryTest<T : OutboxModel> {

    /** The repository implementation being tested */
    internal abstract val repository: OutboxRepository<T>

    /** Method to create a new instance of the model being tested */
    internal abstract fun createWithState(state: String = "test"): T

    /** Method to create a new instance of the model being tested */
    internal abstract fun copyModel(model: T, state: String): T

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     */
    @BeforeEach
    fun setupTest() = runTest {
        repository.deleteAll()
    }

    /**
     * Filters a list of messages to find those that are ready to be processed.
     * A message is ready to process if:
     * - It has PENDING status
     * - Its delayedUntil time has passed
     * - It hasn't exceeded maxAttempts
     */
    private fun List<T>.filterEntitiesToProcess(
        maxAttempts: Int = Int.MAX_VALUE,
        cutoffDate: Instant = Clock.System.now()
    ): List<T> = filter { entity ->
        entity.outBoxStatus == PENDING &&
            (entity.outboxDelayedUntil?.let { it <= cutoffDate } ?: false) &&
            entity.outboxAttemptCount < maxAttempts
    }

    /**
     * Filters a list of messages to find those that are ready to be deleted.
     * A message is ready to delete if:
     * - It has SENT status
     * - Its delayedUntil time is before the cutoff date
     */
    private fun List<T>.filterEntitiesToDelete(
        cutoffDate: Instant = Clock.System.now()
    ): List<T> = filter { entity ->
        entity.outBoxStatus == SENT && (entity.outboxDelayedUntil?.let { it <= cutoffDate } ?: false)
    }

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
        return this.map { it.workflowId }.toSet() == other.map { it.workflowId }.toSet()
    }

    // Test configuration constants
    private val messageCount = 1000
    private val concurrentRequests = 5
    private val limit = 100
    private val maxAttempts = 3
    private val cutoffDate = Clock.System.now() - 7.days

    /**
     * Finds and locks messages that are ready to be processed.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are processed and their status is updated.
     */
    protected open suspend fun findMessagesToProcess(
        maxAttempts: Int = Int.MAX_VALUE,
        limit: Int = Int.MAX_VALUE,
        connection: Connection? = null
    ): List<T> = repository.findEntitiesToProcess(maxAttempts = maxAttempts, limit = limit, connection)

    /**
     * Finds and locks messages that are ready to be deleted.
     * After finding the messages, marks them as FAILED to prevent reprocessing.
     * This simulates a real-world scenario where messages are deleted after processing.
     */
    protected open suspend fun findMessagesToDelete(
        cutoffDate: Instant = Clock.System.now(),
        limit: Int = Int.MAX_VALUE,
        connection: Connection? = null
    ): List<T> = repository.findEntitiesToDelete(cutoffDate = cutoffDate, limit = limit, connection)

    /**
     * Tests that findMessagesToProcess returns the correct messages for processing.
     * Verifies that the repository correctly identifies messages that are:
     * - In PENDING status
     * - Past their delay time
     * - Within attempt limits
     */
    @Test
    fun `findMessagesToProcess should return all eligible pending messages that are ready for processing`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToProcess(Int.MAX_VALUE)
        val expectedIds = expected.map { it.workflowId }
        val actual = findMessagesToProcess().filter { it.workflowId in messagesIds }

        println("expected for processing: ${expected.size}")
        println("  actual for processing: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the maxAttempts parameter.
     * Verifies that messages exceeding the attempt limit are not returned.
     */
    @Test
    fun `findMessagesToProcess should exclude messages that have exceeded maxAttempts`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToProcess(maxAttempts = maxAttempts)
        val expectedIds = expected.map { it.workflowId }

        val actual = findMessagesToProcess(maxAttempts = maxAttempts).filter { it.workflowId in messagesIds }

        println("expected for processing with maxAttempts: ${expected.size}")
        println("  actual for processing with maxAttempts: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToProcess respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for processing.
     */
    @Test
    fun `findMessagesToProcess should respect the limit parameter and only return valid candidates`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToProcess(Int.MAX_VALUE)
        val expectedIds = expected.map { it.workflowId }

        val actual = findMessagesToProcess(limit = limit).filter { it.workflowId in messagesIds }

        actual.size shouldBeLessThanOrEqualTo limit

        println("expected for processing with limit: ${expected.size}")
        println("  actual for processing with limit: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        actual.count { it.workflowId !in expectedIds } shouldBe 0
    }

    /**
     * Tests that findMessagesToDelete returns the correct messages for deletion.
     * Verifies that the repository correctly identifies messages that are:
     * - In SENT status
     * - Past their cutoff date
     */
    @Test
    fun `findMessagesToDelete should return all sent messages that are past their cutoff date`() = runTest {
        val now = Clock.System.now()
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToDelete(cutoffDate = now)
        val expectedIds = expected.map { it.workflowId }

        val actual = findMessagesToDelete(cutoffDate = now).filter { it.workflowId in messagesIds }

        println("expected for deletion: ${expected.size}")
        println("      actual deletion: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the cutoffDate parameter.
     * Verifies that only messages older than the cutoff date are returned.
     */
    @Test
    fun `findMessagesToDelete should only return messages older than the specified cutoff date`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToDelete(cutoffDate = cutoffDate)
        val expectedIds = expected.map { it.workflowId }

        val actual = findMessagesToDelete(cutoffDate = cutoffDate).filter { it.workflowId in messagesIds }

        println("expected for deletion with cutoffDate: ${expected.size}")
        println("      actual deletion with cutoffDate: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        expected.equalTo(actual) shouldBe true
    }

    /**
     * Tests that findMessagesToDelete respects the limit parameter.
     * Verifies that the number of returned messages does not exceed the limit
     * and that all returned messages are valid candidates for deletion.
     */
    @Test
    fun `findMessagesToDelete should respect the limit parameter and only return valid candidates`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expected = messages.filterEntitiesToDelete()
        val expectedIds = expected.map { it.workflowId }

        val actual = findMessagesToDelete(limit = limit).filter { it.workflowId in messagesIds }

        actual.size shouldBeLessThanOrEqualTo limit

        println("expected for deletion with limit: ${expected.size}")
        println("      actual deletion with limit: ${actual.size}")
        actual.filter { it.workflowId !in expectedIds }.forEach { println(it) }
        actual.count { it.workflowId !in expectedIds } shouldBe 0
    }

    /**
     * Tests concurrent message processing to ensure thread safety.
     * Verifies that:
     * - No message is processed more than once
     * - All eligible messages are eventually processed
     * - The operation completes within a reasonable time
     */
    @Test
    fun `findMessagesToProcess should handle concurrent requests without duplicate processing`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expectedProcessed = messages.filterEntitiesToProcess(maxAttempts = maxAttempts).size
        val processedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Clock.System.now() + 1.seconds
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        runBlocking {
                            // run inside a transaction
                            repository.withTransaction { connection ->
                                // get messages to process
                                val results = findMessagesToProcess(maxAttempts, limit, connection)
                                // mark messages as sent
                                results.forEach { it.outBoxStatus = SENT }
                                // save them
                                repository.update(results, connection)
                                // record processed messages
                                synchronized(processedMessages) { processedMessages.addAll(results.filter { it.workflowId in messagesIds }) }
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
        } while (processedMessages.size < expectedProcessed && (Clock.System.now() <= timeout))
        executor.shutdown()

        processedMessages shouldHaveSize expectedProcessed
        val processIds = processedMessages.map { it.workflowId }
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
    fun `findMessagesToDelete should handle concurrent requests without duplicate deletion`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expectedDeleted = messages.filterEntitiesToDelete(cutoffDate).size
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Clock.System.now() + 1.seconds
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        runBlocking {
                            // run inside a transaction
                            repository.withTransaction { connection ->
                                // get messages to delete
                                val results = findMessagesToDelete(cutoffDate, limit, connection)
                                // delete them
                                repository.delete(results, connection)
                                // record deleted messages
                                synchronized(deletedMessages) { deletedMessages.addAll(results.filter { it.workflowId in messagesIds }) }
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
            println("deletedMessages: ${deletedMessages.size} / $expectedDeleted")
        } while (deletedMessages.size < expectedDeleted && (Clock.System.now() <= timeout))
        executor.shutdown()

        deletedMessages shouldHaveSize expectedDeleted
        val processIds = deletedMessages.map { it.workflowId }
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
    fun `should handle mixed concurrent processing and deletion operations without conflicts`() = runTest {
        val messages = insertMessages(messageCount)
        val messagesIds = messages.map { it.workflowId }
        val expectedProcessed = messages.filterEntitiesToProcess(maxAttempts = maxAttempts).size
        val expectedDeleted = messages.filterEntitiesToDelete(cutoffDate).size
        val processedMessages = mutableListOf<T>()
        val deletedMessages = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Clock.System.now() + 1.seconds
        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        // run inside a transaction
                        runBlocking {
                            repository.withTransaction { connection ->
                                when (Random.nextInt(0, 2)) {
                                    0 -> {
                                        println("Processing...")
                                        // get messages to process
                                        val results = findMessagesToProcess(maxAttempts, limit, connection)
                                        // mark messages as sent
                                        results.forEach { it.outBoxStatus = SENT }
                                        // delete them
                                        repository.delete(results, connection)
                                        // record processed messages
                                        synchronized(processedMessages) { processedMessages.addAll(results.filter { it.workflowId in messagesIds }) }
                                    }

                                    1 -> {
                                        println("Deleting...")
                                        // get messages to delete
                                        val results = findMessagesToDelete(cutoffDate, limit, connection)
                                        // delete them
                                        repository.delete(results, connection)
                                        // record deleted messages
                                        synchronized(deletedMessages) { deletedMessages.addAll(results.filter { it.workflowId in messagesIds }) }
                                    }

                                    else -> {}
                                }
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
            Clock.System.now() <= timeout
        )
        executor.shutdown()

        processedMessages shouldHaveSize expectedProcessed
        deletedMessages shouldHaveSize expectedDeleted
        val deletedIds = deletedMessages.map { it.workflowId }
        deletedIds.toSet() shouldHaveSize deletedIds.size
        val processedIds = processedMessages.map { it.workflowId }
        processedIds.toSet() shouldHaveSize processedIds.size

        processedIds.intersect(deletedIds.toSet()).shouldBeEmpty()
    }

    /**
     * Tests that createModel correctly maps ResultSet data to model properties.
     * Verifies that all fields are properly mapped and converted.
     */
    @Test
    fun `createModel should correctly map ResultSet data to model properties`() = runTest {
        // Given
        val expectedId = "test-id"
        val expectedStatus = PENDING
        val expectedDelayedUntil = Clock.System.now() // Truncate for DB precision
        val expectedAttemptCount = 2
        val expectedLastError = "test error"

        val mockResultSet = mockk<ResultSet> {
            // this default behavior will be overwritten by the specific value below
            every { getString(any<String>()) } returns Random.nextBytes(16).toString()
            every { getString("id") } returns expectedId
            every { getString("status") } returns expectedStatus.name
            every { getTimestamp("delayed_until") } returns Timestamp.from(expectedDelayedUntil.toJavaInstant())
            every { getInt("attempt_count") } returns expectedAttemptCount
            every { getString("last_error") } returns expectedLastError
        }

        // When
        val model = repository.createModel(mockResultSet)

        // Then
        model.workflowId shouldBe expectedId
        model.outBoxStatus shouldBe expectedStatus
        model.outboxDelayedUntil shouldBe expectedDelayedUntil
        model.outboxAttemptCount shouldBe expectedAttemptCount
        model.outboxLastError shouldBe expectedLastError
    }

    /**
     * Tests that the repository handles concurrent access correctly.
     * Verifies that concurrent operations don't corrupt the data.
     */
    @Test
    fun `should handle concurrent access correctly`() = runTest {
        // Given
        val messages = List(10) { createWithState("test") }
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
                    runBlocking { repository.insert(messages.subList(start, end)) }
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
        allMessages.map { it.workflowId }.toSet() shouldHaveSize messages.size
    }

    /**
     * Verifies that the repository properly handles concurrent inserts.
     */
    @Test
    fun `insert should handle concurrent transactions correctly`() = runTest {
        // Given
        val message = createWithState().apply {
            outBoxStatus = PENDING
            outboxDelayedUntil = Clock.System.now()
            outboxAttemptCount = 0
        }

        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        // When
        repeat(nThreads) {
            executor.submit {
                try {
                    runBlocking { repository.insert(message) }
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
        val persistedMessage = repository.findById(message.workflowId)
        persistedMessage shouldNotBe null
    }

    /**
     * Verifies that the repository properly handles concurrent updates.
     */
    @Test
    fun `update should handle concurrent transactions correctly`() = runTest {
        // Given
        val original = insertWithState("original")
        val updated = copyModel(original, state = "updated")

        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        // When
        repeat(nThreads) {
            executor.submit {
                try {
                    runBlocking { repository.update(updated) }
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
        val persistedMessage = repository.findById(original.workflowId)
        persistedMessage?.workflowState shouldBe "updated"
    }

    /**
     * Verifies that the count is incremented after messages insertion
     */
    @Test
    fun `count should return 0 for an empty table`() = runTest {
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
    fun `count should be consistent with listAll`() = runTest {
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
    fun `batch delete should successful`() = runTest {
        // Given
        val messages = insertMessages(10)
        val messagesToDelete = messages.take(5)

        // When
        repository.delete(messagesToDelete)

        // Then
        messages.forEachIndexed { index, message ->
            if (index < 5)
                repository.findById(message.workflowId) shouldBe null
            else
                repository.findById(message.workflowId) shouldNotBe null
        }
    }

    /**
     * Tests messages creation.
     */
    @Test
    fun `batch updates should successful`() = runTest {
        // Given
        val messages = insertMessages(10)
        val messagesToUpdate = messages.take(5)
        repository.insert(messages)

        // update by changing the status of all messages
        messagesToUpdate.forEach { it.outboxAttemptCount = 100 }

        // When
        repository.update(messagesToUpdate) shouldBe 5

        // Then
        messages.forEachIndexed { index, message ->
            if (index < 5)
                repository.findById(message.workflowId)!!.outboxAttemptCount shouldBe 100
            else
                repository.findById(message.workflowId)!!.outboxAttemptCount shouldBeLessThan 100
        }
    }

    // --- Tests for persist(entity, force) ---

    @Test
    fun `insert new entity should be successful`() = runTest {
        val original = createWithState("original")

        repository.insert(original) shouldBe 1

        val retrieved = repository.findById(original.workflowId)
        retrieved shouldNotBe null
        retrieved?.workflowState shouldBe original.workflowState
    }

    @Test
    fun `insert existing entity should fail`() = runTest {
        val original = insertWithState("original")
        val updated = copyModel(original, state = "updated")

        repository.insert(updated) shouldBe 0

        val retrieved = repository.findById(original.workflowId)
        retrieved shouldNotBe null
        retrieved?.workflowState shouldBe original.workflowState
    }

    @Test
    fun `update existing entity should be successful`() = runTest {
        val original = insertWithState("original")
        val updated = copyModel(original, state = "updated")

        repository.update(updated) shouldBe 1

        val retrieved = repository.findById(original.workflowId)
        retrieved shouldNotBe null
        retrieved?.workflowState shouldBe "updated"
    }

    @Test
    fun `update new entity should fail`() = runTest {
        val original = createWithState("original")

        repository.update(original) shouldBe 0

        val retrieved = repository.findById(original.workflowId)
        retrieved shouldBe null
    }

    // --- Tests for persist(entities, force) ---

    @Test
    fun `update list should update only existing`() = runTest {
        val original = insertWithState("original-0")
        val updated = copyModel(original, state = "updated")
        val newEntity1 = createWithState("original-1")
        val newEntity2 = createWithState("original-2")

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.update(entitiesToPersist) shouldBe 1

        // Verify updates
        val retrievedUpdated = repository.findById(original.workflowId)
        retrievedUpdated?.workflowState shouldBe "updated"

        // Verify inserts
        repository.findById(newEntity1.workflowId) shouldBe null
        repository.findById(newEntity2.workflowId) shouldBe null
    }

    @Test
    fun `insert list should insert only non-existing`() = runTest {
        val original = insertWithState("original-0")
        val updated = copyModel(original, state = "updated")
        val newEntity1 = createWithState("original-1")
        val newEntity2 = createWithState("original-2")

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.insert(entitiesToPersist) shouldBe 2

        // Verify updates
        val retrievedUpdated = repository.findById(original.workflowId)
        retrievedUpdated?.workflowState shouldBe "original-0"

        // Verify inserts
        repository.findById(newEntity1.workflowId) shouldNotBe null
        repository.findById(newEntity2.workflowId) shouldNotBe null
    }

    @Test
    fun `delete should remove an existing message`() = runTest {
        // Given
        val message = createWithState("to-delete")
        repository.insert(message)

        // When
        val deletedCount = repository.delete(message)

        // Then
        deletedCount shouldBe 1
        repository.findById(message.workflowId) shouldBe null
    }

    @Test
    fun `delete should return 0 if message does not exist`() = runTest {
        // Given
        val existingMessage = createWithState("existing")
        val nonExistentMessage = createWithState("non-existent")
        repository.insert(existingMessage)

        // When
        val deletedCount = repository.delete(nonExistentMessage)

        // Then
        deletedCount shouldBe 0
        repository.findById(existingMessage.workflowId) shouldNotBe null
    }

    @Test
    fun `batch delete should remove multiple existing messages`() = runTest {
        // Given
        val messagesToDelete = List(5) { createWithState("delete-$it") }
        val otherMessage = createWithState("keep-me")
        repository.insert(messagesToDelete + otherMessage)

        // When
        val deletedCount = repository.delete(messagesToDelete)

        // Then
        deletedCount shouldBe 5
        repository.findById(otherMessage.workflowId) shouldNotBe null
        messagesToDelete.forEach {
            repository.findById(it.workflowId) shouldBe null
        }
    }

    @Test
    fun `batch delete should return correct count when some messages do not exist`() = runTest {
        // Given
        val existingMessages = List(3) { createWithState("existing-$it") }
        val nonExistentMessages = List(2) { createWithState("non-existent-$it") }
        repository.insert(existingMessages)

        val batchToDelete = existingMessages.take(2) + nonExistentMessages

        // When
        val deletedCount = repository.delete(batchToDelete)

        // Then
        deletedCount shouldBe 2 // Only the 2 existing messages should be counted as deleted
        repository.findById(existingMessages[0].workflowId) shouldBe null
        repository.findById(existingMessages[1].workflowId) shouldBe null
        repository.findById(existingMessages[2].workflowId) shouldNotBe null
    }

    @Test
    fun `batch delete should return 0 for an empty list`() = runTest {
        // Given
        repository.insert(createWithState("existing"))
        val emptyList = emptyList<T>()

        // When
        val deletedCount = repository.delete(emptyList)

        // Then
        deletedCount shouldBe 0
    }

    /**
     * Creates a batch of test messages with randomized properties.
     * Each message has:
     * - Random status (PENDING, SENT, or FAILED)
     * - Random delay duration
     * - Random attempt count
     * - Sequential message content
     */
    private suspend fun insertMessages(count: Int): List<T> {
        val now = Clock.System.now()
        val messages = List(count) { i ->
            val duration = randomNonZero(1000).hours
            val status = when (Random.nextInt(0, 2)) {
                0 -> PENDING
                1 -> SENT
                else -> FAILED
            }
            val attemptCount = Random.nextInt(0, 5)

            createWithState("test$i").apply {
                this.outBoxStatus = status
                this.outboxDelayedUntil = now + duration
                this.outboxAttemptCount = attemptCount
            }
        }
        repository.insert(messages)
        return messages
    }

    // --- Helper to persist an entity for setup ---
    private suspend fun insertWithState(state: String): T {
        val entity = createWithState(state)
        repository.insert(entity)
        return entity
    }
}
