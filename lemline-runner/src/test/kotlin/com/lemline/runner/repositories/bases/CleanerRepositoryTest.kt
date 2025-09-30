// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.values.IDV7
import com.lemline.runner.models.bases.CleanerModel
import com.lemline.runner.models.bases.CleanerModelBase
import com.lemline.runner.models.bases.OptionalCleanerModel
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.outbox.bases.RunStatus.DONE
import com.lemline.runner.outbox.bases.RunStatus.FAILED
import com.lemline.runner.outbox.bases.RunStatus.PENDING
import com.lemline.runner.random.random
import io.kotest.common.runBlocking
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@ExperimentalTime
internal val CleanerModelBase.runAt
    get() = when (this) {
        is OptionalCleanerModel -> runAt
        is CleanerModel -> runAt
        else -> error("Unknown cleaner model $this")
    }

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
@ExperimentalSerializationApi
internal abstract class CleanerRepositoryTest<T : CleanerModelBase> {

    /** The repository implementation being tested */
    internal abstract val repository: CleanerRepositoryBase<T>

    /** Method to create a new instance of the model being tested */
    abstract fun createRandomEntity(
        runStatus: RunStatus = RunStatus.random(),
        runAt: Instant = Clock.System.now()
    ): T

    /** Method to create a new instance of the model being tested */
    internal abstract fun modify(model: T): T

    /**
     * Cleans up the database before each test to ensure a clean state.
     * This is crucial for maintaining test isolation and reliability.
     */
    @BeforeEach
    fun setupTest() = runTest {
        repository.deleteAll()
    }

    /**
     * Filters a list of messages to find those that are ready to be deleted.
     * A message is ready to delete if:
     * - It has SENT status
     * - Its delayedUntil time is before the cutoff date
     */
    private fun List<T>.findEntitiesToDelete(
        cutoffDate: Instant = Clock.System.now()
    ): List<T> = filter { entity ->
        entity.runStatus == DONE && (entity.runAt?.let { it <= cutoffDate } ?: false)
    }

    /**
     * Finds an entity in the list with the specified ID. If multiple entities with
     * the same ID exist, an error is thrown. If no entity with the given ID is found,
     * returns null.
     */
    private fun List<T>.findById(id: IDV7): T? {
        val filtered = filter { it.id == id }
        if (filtered.size > 1) error("Multiple entities found with id=$id")
        return filtered.firstOrNull()
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
        return this.map { it.id }.toSet() == other.map { it.id }.toSet()
    }

    // Test configuration constants
    private val messageCount = 1000
    private val concurrentRequests = 5
    private val limit = 100
    private val cutoffDate = Clock.System.now() - 7.days

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
     * Tests that findMessagesToDelete returns the correct messages for deletion.
     * Verifies that the repository correctly identifies messages that are:
     * - In SENT status
     * - Past their cutoff date
     */
    @Test
    fun `findMessagesToDelete should return all sent messages that are past their cutoff date`() = runTest {
        val now = Clock.System.now()
        val messages = insertEntities(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.findEntitiesToDelete(cutoffDate = now)
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
    fun `findMessagesToDelete should only return messages older than the specified cutoff date`() = runTest {
        val messages = insertEntities(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.findEntitiesToDelete(cutoffDate = cutoffDate)
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
    fun `findMessagesToDelete should respect the limit parameter and only return valid candidates`() = runTest {
        val messages = insertEntities(messageCount)
        val messagesIds = messages.map { it.id }
        val expected = messages.findEntitiesToDelete()
        val expectedIds = expected.map { it.id }

        val actual = findMessagesToDelete(limit = limit).filter { it.id in messagesIds }

        actual.size shouldBeLessThanOrEqualTo limit

        println("expected for deletion with limit: ${expected.size}")
        println("      actual deletion with limit: ${actual.size}")
        actual.filter { it.id !in expectedIds }.forEach { println(it) }
        actual.count { it.id !in expectedIds } shouldBe 0
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
        val messages = insertEntities(messageCount)
        val messagesIds = messages.map { it.id }
        val expectedDeleted = messages.findEntitiesToDelete(cutoffDate).size
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
                                synchronized(deletedMessages) { deletedMessages.addAll(results.filter { it.id in messagesIds }) }
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
        val processIds = deletedMessages.map { it.id }
        processIds.toSet() shouldHaveSize processIds.size
    }

    /**
     * Tests that the repository handles concurrent access correctly.
     * Verifies that concurrent operations don't corrupt the data.
     */
    @Test
    fun `should handle concurrent insert correctly`() = runTest {
        // Given
        val messages = List(10) { createRandomEntity() }
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
        allMessages.map { it.id }.toSet() shouldHaveSize messages.size
    }

    /**
     * Verifies that the repository properly handles concurrent inserts.
     */
    @Test
    fun `insert should handle concurrent transactions correctly`() = runTest {
        // Given
        val message = createRandomEntity(
            runStatus = PENDING,
            runAt = Clock.System.now(),
        )

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
        val persistedMessage = repository.findById(message.id)
        persistedMessage shouldNotBe null
    }

    /**
     * Verifies that the repository properly handles concurrent updates.
     */
    @Test
    fun `update should handle concurrent transactions correctly`() = runTest {
        // Given
        val original = insertRandomEntity()
        val updated = modify(original)

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
        val persistedMessage = repository.findById(original.id)
        persistedMessage shouldBe updated
    }

    /**
     * Verifies that the count is incremented after messages insertion
     */
    @Test
    fun `count should return 0 for an empty table`() = runTest {
        // Given
        val count = repository.countAll()

        // When
        insertEntities(10)

        // Then
        repository.countAll() shouldBe count + 10
    }

    /**
     * Tests that count() is consistent with listAll().
     * Verifies that the count matches the number of records returned by listAll.
     */
    @Test
    fun `count should be consistent with listAll`() = runTest {
        // Given
        insertEntities(10)

        // When
        val count = repository.countAll()
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
        val messages = insertEntities(10)
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
    fun `batch updates should successful`() = runTest {
        // Given
        val messages = insertEntities(10)
        val messagesToUpdate = messages.take(5)
        repository.insert(messages)

        // update by changing the status of all messages
        val updatedMessages = messagesToUpdate.map { modify(it) }

        // When
        repository.update(updatedMessages) shouldBe 5

        // Then
        messages.forEachIndexed { index, message ->
            if (index < 5)
                repository.findById(message.id) shouldBe updatedMessages.findById(message.id)
            else
                repository.findById(message.id) shouldBe messages.findById(message.id)
        }
    }

    // --- Tests for persist(entity, force) ---

    @Test
    fun `insert new entity should be successful`() = runTest {
        val original = createRandomEntity()

        repository.insert(original) shouldBe 1

        val retrieved = repository.findById(original.id)
        retrieved shouldBe original
    }

    @Test
    fun `insert existing entity should fail`() = runTest {
        val original = insertRandomEntity()
        val updated = modify(original)

        repository.insert(updated) shouldBe 0

        val retrieved = repository.findById(original.id)
        retrieved shouldNotBe null
        retrieved shouldBe original
    }

    @Test
    fun `update existing entity should be successful`() = runTest {
        val original: T = insertRandomEntity()
        val updated = modify(original)

        repository.update(updated) shouldBe 1

        val retrieved = repository.findById(original.id)
        retrieved shouldBe updated
    }

    @Test
    fun `update new entity should fail`() = runTest {
        val original = createRandomEntity()

        repository.update(original) shouldBe 0

        val retrieved = repository.findById(original.id)
        retrieved shouldBe null
    }

    // --- Tests for persist(entities, force) ---

    @Test
    fun `update list should update only existing`() = runTest {
        val original = insertRandomEntity()
        val updated = modify(original)
        val newEntity1 = createRandomEntity()
        val newEntity2 = createRandomEntity()

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.update(entitiesToPersist) shouldBe 1

        // Verify updates
        val retrievedUpdated = repository.findById(original.id)
        retrievedUpdated shouldBe updated

        // Verify inserts
        repository.findById(newEntity1.id) shouldBe null
        repository.findById(newEntity2.id) shouldBe null
    }

    @Test
    fun `insert list should insert only non-existing`() = runTest {
        val original = insertRandomEntity()
        val updated = modify(original)
        val newEntity1 = createRandomEntity()
        val newEntity2 = createRandomEntity()

        val entitiesToPersist = listOf(newEntity1, updated, newEntity2)

        repository.insert(entitiesToPersist) shouldBe 2

        // Verify updates
        val retrievedUpdated = repository.findById(original.id)
        retrievedUpdated shouldBe original

        // Verify inserts
        repository.findById(newEntity1.id) shouldNotBe null
        repository.findById(newEntity2.id) shouldNotBe null
    }

    @Test
    fun `delete should remove an existing message`() = runTest {
        // Given
        val message = insertRandomEntity()

        // When
        val deletedCount = repository.delete(message)

        // Then
        deletedCount shouldBe 1
        repository.findById(message.id) shouldBe null
    }

    @Test
    fun `delete should return 0 if message does not exist`() = runTest {
        // Given
        val existingMessage = createRandomEntity()
        val nonExistentMessage = createRandomEntity()
        repository.insert(existingMessage)

        // When
        val deletedCount = repository.delete(nonExistentMessage)

        // Then
        deletedCount shouldBe 0
        repository.findById(existingMessage.id) shouldNotBe null
    }

    @Test
    fun `deleteById should remove an existing message`() = runTest {
        // Given
        val message = insertRandomEntity()

        // When
        val deletedCount = repository.deleteById(message.id)

        // Then
        deletedCount shouldBe 1
        repository.findById(message.id) shouldBe null
    }

    @Test
    fun `deleteById should return 0 if message does not exist`() = runTest {
        // Given
        val existingMessage = createRandomEntity()
        repository.insert(existingMessage)
        val randomId = createRandomEntity().id

        // When
        val deletedCount = repository.deleteById(randomId)

        // Then
        deletedCount shouldBe 0
        repository.findById(existingMessage.id) shouldNotBe null
    }

    @Test
    fun `batch delete should remove multiple existing messages`() = runTest {
        // Given
        val messagesToDelete = List(5) { createRandomEntity() }
        val otherMessage = createRandomEntity()
        repository.insert(messagesToDelete + otherMessage)

        // When
        val deletedCount = repository.delete(messagesToDelete)

        // Then
        deletedCount shouldBe 5
        repository.findById(otherMessage.id) shouldNotBe null
        messagesToDelete.forEach {
            repository.findById(it.id) shouldBe null
        }
    }

    @Test
    fun `batch delete should return correct count when some messages do not exist`() = runTest {
        // Given
        val existingMessages = List(3) { createRandomEntity() }
        val nonExistentMessages = List(2) { createRandomEntity() }
        repository.insert(existingMessages)

        val batchToDelete = existingMessages.take(2) + nonExistentMessages

        // When
        val deletedCount = repository.delete(batchToDelete)

        // Then
        deletedCount shouldBe 2 // Only the 2 existing messages should be counted as deleted
        repository.findById(existingMessages[0].id) shouldBe null
        repository.findById(existingMessages[1].id) shouldBe null
        repository.findById(existingMessages[2].id) shouldNotBe null
    }

    @Test
    fun `batch delete should return 0 for an empty list`() = runTest {
        // Given
        repository.insert(createRandomEntity())
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
    private suspend fun insertEntities(count: Int): List<T> {
        val now = Clock.System.now()
        val messages = List(count) { i ->
            val duration = randomNonZero(1000).hours
            val status = when (Random.nextInt(0, 2)) {
                0 -> PENDING
                1 -> DONE
                else -> FAILED
            }
            val attemptCount = Random.nextInt(0, 5)

            createRandomEntity(
                runStatus = status,
                runAt = now + duration
            )
        }
        repository.insert(messages)
        return messages
    }

    // --- Helper to persist an entity for setup ---
    private suspend fun insertRandomEntity(): T {
        val entity = createRandomEntity()
        repository.insert(entity)
        return entity
    }
}
