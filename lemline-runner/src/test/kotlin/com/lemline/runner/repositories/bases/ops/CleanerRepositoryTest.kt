// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases.ops

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WithCleanup
import com.lemline.runner.repositories.with.WithCleanerRepository
import com.lemline.runner.repositories.with.WithCrudRepository
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Concrete test class for cleaner repository operations.
 * Tests findEntitiesToDelete method.
 *
 * Use as a @Nested inner class in concrete repository tests.
 *
 * @param T The entity type being tested (must implement WithCleanup)
 * @param cleanerRepository Provider for cleaner operations (uses lazy eval to handle @Inject timing)
 * @param crudRepository Provider for CRUD operations needed for setup
 * @param createEntity Factory function to create random test entities
 * @param getEntityKey Function to extract unique key from entity for comparison
 * @param databaseManager Provider for database manager
 */
@ExperimentalTime
@ExperimentalSerializationApi
abstract class CleanerRepositoryTest<T : WithCleanup>(
    cleanerRepository: () -> WithCleanerRepository<T>,
    crudRepository: () -> WithCrudRepository<T>,
    private val createEntity: () -> T,
    private val getEntityKey: (T) -> Any,
    databaseManager: () -> DatabaseManager
) {
    private val cleanerRepository: WithCleanerRepository<T> by lazy { cleanerRepository() }
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }
    private val databaseManager: DatabaseManager by lazy { databaseManager() }

    // Test configuration
    private val messageCount = 100
    private val concurrentRequests = 5
    private val limit = 20
    private val cutoffDate = Clock.System.now() - 7.days

    @BeforeEach
    fun setupCleanerTest() = runTest {
        crudRepository.deleteAll()
    }

    // ========== Helper Methods ==========

    private fun List<T>.filterEntitiesToDelete(
        cutoffDate: Instant = Clock.System.now()
    ): List<T> = filter { entity ->
        entity.cleanupAfter != null && entity.cleanupAfter!! < cutoffDate
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
            val hasCleanupAfter = Random.nextBoolean()

            createEntity().apply {
                cleanupAfter = if (hasCleanupAfter) now - duration else null
            }
        }
        crudRepository.insert(entities)
        return entities
    }

    // ========== findEntitiesToDelete Tests ==========

    @Test
    fun `findEntitiesToDelete should return all entities past cutoff`() = runTest {
        val now = Clock.System.now()
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToDelete(cutoffDate = now)

        val actual = cleanerRepository.findEntitiesToDelete(now, Int.MAX_VALUE, null)
            .filter { getEntityKey(it) in entityKeys }

        expected.keysEqual(actual) shouldBe true
    }

    @Test
    fun `findEntitiesToDelete should only return entities older than cutoff date`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToDelete(cutoffDate = cutoffDate)

        val actual = cleanerRepository.findEntitiesToDelete(cutoffDate, Int.MAX_VALUE, null)
            .filter { getEntityKey(it) in entityKeys }

        expected.keysEqual(actual) shouldBe true
    }

    @Test
    fun `findEntitiesToDelete should respect the batchSize parameter`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expected = entities.filterEntitiesToDelete()
        val expectedKeys = expected.map { getEntityKey(it) }

        val actual = cleanerRepository.findEntitiesToDelete(Clock.System.now(), limit, null)
            .filter { getEntityKey(it) in entityKeys }

        actual.size shouldBeLessThanOrEqualTo limit
        // All returned should be valid candidates
        actual.count { getEntityKey(it) !in expectedKeys } shouldBe 0
    }

    @Test
    fun `findEntitiesToDelete should not return entities without cleanupAfter`() = runTest {
        val entity = createEntity().apply {
            cleanupAfter = null
        }
        crudRepository.insert(entity)

        val result = cleanerRepository.findEntitiesToDelete(Clock.System.now(), Int.MAX_VALUE, null)

        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToDelete should not return entities with future cleanupAfter`() = runTest {
        val entity = createEntity().apply {
            cleanupAfter = Clock.System.now() + 1.days // Future
        }
        crudRepository.insert(entity)

        // Use current time as cutoff
        val result = cleanerRepository.findEntitiesToDelete(Clock.System.now(), Int.MAX_VALUE, null)

        result.shouldBeEmpty()
    }

    // ========== Concurrent Operation Tests ==========

    @Test
    fun `findEntitiesToDelete should handle concurrent requests without duplicates`() = runTest {
        val entities = insertTestEntities(messageCount)
        val entityKeys = entities.map { getEntityKey(it) }
        val expectedCount = entities.filterEntitiesToDelete(cutoffDate).size
        val deletedEntities = mutableListOf<T>()
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val timeout = Clock.System.now() + 2.seconds

        do {
            val latch = CountDownLatch(concurrentRequests)
            repeat(concurrentRequests) {
                executor.submit {
                    try {
                        runBlocking {
                            databaseManager.withTransaction { connection ->
                                val results = cleanerRepository.findEntitiesToDelete(cutoffDate, limit, connection)
                                crudRepository.delete(results, connection)
                                synchronized(deletedEntities) {
                                    deletedEntities.addAll(results.filter { getEntityKey(it) in entityKeys })
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore errors
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(1, TimeUnit.SECONDS)
        } while (deletedEntities.size < expectedCount && Clock.System.now() <= timeout)
        executor.shutdown()

        deletedEntities shouldHaveSize expectedCount
        // No duplicates
        val deletedKeys = deletedEntities.map { getEntityKey(it) }
        deletedKeys.toSet() shouldHaveSize deletedKeys.size
    }
}
