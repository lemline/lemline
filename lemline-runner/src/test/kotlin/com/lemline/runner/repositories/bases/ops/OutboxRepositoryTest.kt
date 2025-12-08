// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases.ops

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WithOutbox
import com.lemline.runner.repositories.with.WithCrudRepository
import com.lemline.runner.repositories.with.WithOutboxRepository
import io.kotest.common.runBlocking
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Concrete test class for outbox-specific repository operations.
 * Tests findEntitiesToProcess method.
 *
 * Use as a @Nested inner class in concrete repository tests.
 *
 * @param T The entity type being tested (must implement WithOutbox)
 * @param outboxRepository Provider for outbox operations (uses lazy eval to handle @Inject timing)
 * @param crudRepository Provider for CRUD operations needed for setup
 * @param createEntity Factory function to create random test entities
 * @param getEntityKey Function to extract unique key from entity for comparison
 * @param databaseManager Provider for database manager
 */
@ExperimentalTime
@ExperimentalSerializationApi
abstract class OutboxRepositoryTest<T : WithOutbox>(
    outboxRepository: () -> WithOutboxRepository<T>,
    crudRepository: () -> WithCrudRepository<T>,
    private val createEntity: () -> T,
    private val getEntityKey: (T) -> Any,
    databaseManager: () -> DatabaseManager
) {
    private val outboxRepository: WithOutboxRepository<T> by lazy { outboxRepository() }
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }
    private val databaseManager: DatabaseManager by lazy { databaseManager() }

    // Test configuration
    private val messageCount = 100
    private val concurrentRequests = 5
    private val limit = 20
    private val maxAttempts = 3

    @BeforeEach
    fun setupOutboxTest() = runTest {
        crudRepository.deleteAll()
    }

    // ========== Helper Methods ==========

    private fun List<T>.filterEntitiesToProcess(
        maxAttempts: Int = Int.MAX_VALUE,
        cutoffDate: Instant = Clock.System.now()
    ): List<T> = filter { entity ->
        entity.outboxCompletedAt == null &&
            entity.outboxFailedAt == null &&
            (entity.outboxDelayedUntil?.let { it <= cutoffDate } ?: false) &&
            entity.outboxAttemptCount < maxAttempts
    }

    private fun randomNonZero(n: Int): Int {
        val r = Random.nextInt(-n, n - 1)
        return if (r >= 0) r + 1 else r
    }

    private fun List<T>.keysEqual(other: List<T>): Boolean {
        return this.map { getEntityKey(it) }.toSet() == other.map { getEntityKey(it) }.toSet()
    }

    private suspend fun insertTestEntities(count: Int): List<T> {
        val now = Clock.System.now()
        val entities = List(count) {
            val duration = randomNonZero(1000).hours
            val state = Random.nextInt(0, 3)
            val attemptCount = Random.nextInt(0, 5)

            createEntity().apply {
                when (state) {
                    0 -> { // PENDING
                        outboxCompletedAt = null
                        outboxFailedAt = null
                    }

                    1 -> { // COMPLETED
                        outboxCompletedAt = now - duration
                        outboxFailedAt = null
                    }

                    else -> { // FAILED
                        outboxCompletedAt = null
                        outboxFailedAt = now - duration
                    }
                }
                outboxDelayedUntil = now - duration
                outboxAttemptCount = attemptCount
            }
        }
        crudRepository.insert(entities)
        return entities
    }

    // ========== findEntitiesToProcess Tests ==========

    @Test
    fun `findEntitiesToProcess should return all eligible pending entities`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToProcess(Int.MAX_VALUE)

        val actual = outboxRepository.findEntitiesToProcess(Int.MAX_VALUE, Int.MAX_VALUE, null)
            .filter { getEntityKey(it) in entityKeys }

        expected.keysEqual(actual) shouldBe true
    }

    @Test
    fun `findEntitiesToProcess should exclude entities exceeding maxAttempts`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToProcess(maxAttempts = maxAttempts)

        val actual = outboxRepository.findEntitiesToProcess(maxAttempts, Int.MAX_VALUE, null)
            .filter { getEntityKey(it) in entityKeys }

        expected.keysEqual(actual) shouldBe true
    }

    @Test
    fun `findEntitiesToProcess should respect the limit parameter`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToProcess(Int.MAX_VALUE)
        val expectedKeys = expected.map { getEntityKey(it) }

        val actual = outboxRepository.findEntitiesToProcess(Int.MAX_VALUE, limit, null)
            .filter { getEntityKey(it) in entityKeys }

        actual.size shouldBeLessThanOrEqualTo limit
        // All returned should be valid candidates
        actual.count { getEntityKey(it) !in expectedKeys } shouldBe 0
    }

    @Test
    fun `findEntitiesToProcess should not return completed entities`() = runTest {
        val entity = createEntity().apply {
            outboxCompletedAt = Clock.System.now()
            outboxFailedAt = null
            outboxDelayedUntil = Clock.System.now() - 1.hours
            outboxAttemptCount = 0
        }
        crudRepository.insert(entity)

        val result = outboxRepository.findEntitiesToProcess(Int.MAX_VALUE, Int.MAX_VALUE, null)

        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToProcess should not return failed entities`() = runTest {
        val entity = createEntity().apply {
            outboxCompletedAt = null
            outboxFailedAt = Clock.System.now()
            outboxDelayedUntil = Clock.System.now() - 1.hours
            outboxAttemptCount = 0
        }
        crudRepository.insert(entity)

        val result = outboxRepository.findEntitiesToProcess(Int.MAX_VALUE, Int.MAX_VALUE, null)

        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToProcess should not return entities not yet due`() = runTest {
        val entity = createEntity().apply {
            outboxCompletedAt = null
            outboxFailedAt = null
            outboxDelayedUntil = Clock.System.now() + 1.hours // Future
            outboxAttemptCount = 0
        }
        crudRepository.insert(entity)

        val result = outboxRepository.findEntitiesToProcess(Int.MAX_VALUE, Int.MAX_VALUE, null)

        result.shouldBeEmpty()
    }

    // ========== Concurrent Operation Tests ==========

    @Test
    fun `findEntitiesToProcess should handle concurrent requests without duplicates`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expectedCount = entities.filterEntitiesToProcess(maxAttempts = maxAttempts).size
        val processedEntities = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Clock.System.now() + 2.seconds

        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        runBlocking {
                            databaseManager.withTransaction { connection ->
                                val results = outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)
                                // Mark as completed to prevent reprocessing
                                results.forEach { it.outboxCompletedAt = Clock.System.now() }
                                crudRepository.update(results, connection)
                                synchronized(processedEntities) {
                                    processedEntities.addAll(results.filter { getEntityKey(it) in entityKeys })
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
        } while (processedEntities.size < expectedCount && Clock.System.now() <= timeout)
        executor.shutdown()

        processedEntities shouldHaveSize expectedCount
        // No duplicates
        val processedKeys = processedEntities.map { getEntityKey(it) }
        processedKeys.toSet() shouldHaveSize processedKeys.size
    }
}
