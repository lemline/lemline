// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test.ops

import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test


/**
 * Concrete test class for CRUD repository operations.
 * Tests insert, update, delete, listAll, countAll, and withTransaction methods.
 *
 * Use as a @Nested inner class in concrete repository tests.
 *
 * @param T The entity type being tested
 * @param crudRepository Provider for CRUD operations (uses lazy eval to handle @Inject timing)
 * @param createEntity Factory function to create random test entities
 * @param modifyEntity Function to modify an entity for update tests
 */
abstract class CrudRepositoryTest<T : Any>(
    crudRepository: () -> WithCrudRepository<T>,
    private val createEntity: () -> T,
    private val modifyEntity: (T) -> T
) {
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }

    @BeforeEach
    fun setupCrudTest() = runTest {
        crudRepository.deleteAll()
    }

    // ========== Insert Tests ==========

    @Test
    fun `insert new entity should be successful`() = runTest {
        val entity = createEntity()

        crudRepository.insert(entity) shouldBe 1

        val all = crudRepository.listAll()
        all shouldHaveSize 1
    }

    @Test
    fun `insert existing entity should return 0`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        crudRepository.insert(entity) shouldBe 0

        crudRepository.countAll() shouldBe 1
    }

    @Test
    fun `insert list should insert all new entities`() = runTest {
        val entities = List(5) { createEntity() }

        crudRepository.insert(entities) shouldBe 5

        crudRepository.countAll() shouldBe 5
    }

    @Test
    fun `insert list should insert only non-existing entities`() = runTest {
        val existing = createEntity()
        crudRepository.insert(existing)

        val newEntities = List(3) { createEntity() }
        val mixed = listOf(existing) + newEntities

        crudRepository.insert(mixed) shouldBe 3

        crudRepository.countAll() shouldBe 4
    }

    // ========== Update Tests ==========

    @Test
    fun `update existing entity should be successful`() = runTest {
        val original = createEntity()
        crudRepository.insert(original)

        val modified = modifyEntity(original)
        crudRepository.update(modified) shouldBe 1
    }

    @Test
    fun `update non-existing entity should return 0`() = runTest {
        val entity = createEntity()

        crudRepository.update(entity) shouldBe 0
    }

    @Test
    fun `update list should update only existing entities`() = runTest {
        val existing = createEntity()
        crudRepository.insert(existing)

        val newEntities = List(2) { createEntity() }
        val mixed = listOf(modifyEntity(existing)) + newEntities

        crudRepository.update(mixed) shouldBe 1
    }

    // ========== Delete Tests ==========

    @Test
    fun `delete existing entity should be successful`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        crudRepository.delete(entity) shouldBe 1
        crudRepository.countAll() shouldBe 0
    }

    @Test
    fun `delete non-existing entity should return 0`() = runTest {
        val entity = createEntity()

        crudRepository.delete(entity) shouldBe 0
    }

    @Test
    fun `delete list should delete all existing entities`() = runTest {
        val entities = List(5) { createEntity() }
        crudRepository.insert(entities)

        crudRepository.delete(entities.take(3)) shouldBe 3
        crudRepository.countAll() shouldBe 2
    }

    @Test
    fun `delete list should return count of actually deleted entities`() = runTest {
        val existing = List(3) { createEntity() }
        crudRepository.insert(existing)

        val nonExisting = List(2) { createEntity() }
        val mixed = existing.take(2) + nonExisting

        crudRepository.delete(mixed) shouldBe 2
        crudRepository.countAll() shouldBe 1
    }

    @Test
    fun `delete empty list should return 0`() = runTest {
        crudRepository.insert(createEntity())

        crudRepository.delete(emptyList()) shouldBe 0
        crudRepository.countAll() shouldBe 1
    }

    @Test
    fun `deleteAll should remove all entities`() = runTest {
        crudRepository.insert(List(5) { createEntity() })

        crudRepository.deleteAll()

        crudRepository.countAll() shouldBe 0
    }

    // ========== List and Count Tests ==========

    @Test
    fun `listAll should return all entities`() = runTest {
        val entities = List(5) { createEntity() }
        crudRepository.insert(entities)

        val all = crudRepository.listAll()
        all shouldHaveSize 5
    }

    @Test
    fun `listAll should return empty list when no entities`() = runTest {
        crudRepository.listAll().shouldBeEmpty()
    }

    @Test
    fun `countAll should return correct count`() = runTest {
        crudRepository.countAll() shouldBe 0

        crudRepository.insert(List(10) { createEntity() })

        crudRepository.countAll() shouldBe 10
    }

    @Test
    fun `count should be consistent with listAll`() = runTest {
        crudRepository.insert(List(10) { createEntity() })

        val count = crudRepository.countAll()
        val all = crudRepository.listAll()

        count shouldBe all.size.toLong()
    }

    // ========== Concurrent Operation Tests ==========

    @Test
    fun `concurrent inserts should not lose data`() = runTest {
        val entities = List(10) { createEntity() }
        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        repeat(nThreads) { threadIndex ->
            executor.submit {
                try {
                    val start = threadIndex * 2
                    val end = start + 2
                    runBlocking { crudRepository.insert(entities.subList(start, end)) }
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        exceptions.shouldBeEmpty()
        crudRepository.listAll() shouldHaveSize entities.size
    }

    @Test
    fun `concurrent updates should not corrupt data`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        val modified = modifyEntity(entity)
        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        repeat(nThreads) {
            executor.submit {
                try {
                    runBlocking { crudRepository.update(modified) }
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        exceptions.shouldBeEmpty()
        crudRepository.countAll() shouldBe 1
    }

    @Test
    fun `idempotent insert should handle concurrent transactions`() = runTest {
        val entity = createEntity()

        val nThreads = 5
        val executor = Executors.newFixedThreadPool(nThreads)
        val latch = CountDownLatch(nThreads)
        val exceptions = mutableListOf<Exception>()

        repeat(nThreads) {
            executor.submit {
                try {
                    runBlocking { crudRepository.insert(entity) }
                } catch (e: Exception) {
                    synchronized(exceptions) { exceptions.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        exceptions.shouldBeEmpty()
        crudRepository.countAll() shouldBe 1
    }
}
