// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test.ops

import com.lemline.common.values.IDV7
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Interface for ID-based repository operations used in testing.
 */
@ExperimentalTime
interface IdRepositoryOps<T : WithId> {
    suspend fun findById(id: IDV7, connection: Connection? = null): T?
    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int
}

/**
 * Concrete test class for ID-based repository operations.
 * Tests findById and deleteById methods.
 *
 * Use as a @Nested inner class in concrete repository tests.
 *
 * @param T The entity type being tested (must implement WithId)
 * @param idRepository Provider for ID operations (uses lazy eval to handle @Inject timing)
 * @param crudRepository Provider for CRUD operations needed for setup
 * @param createEntity Factory function to create random test entities
 */
@ExperimentalTime
abstract class IdRepositoryTest<T : WithId>(
    idRepository: () -> WithIdRepository<T>,
    crudRepository: () -> WithCrudRepository<T>,
    private val createEntity: () -> T
) {
    private val idRepository: WithIdRepository<T> by lazy { idRepository() }
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }

    @BeforeEach
    fun setupIdTest() = runTest {
        crudRepository.deleteAll()
    }

    // ========== FindById Tests ==========

    @Test
    fun `findById should return entity when it exists`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        val found = idRepository.findById(entity.id)

        found shouldNotBe null
        found!!.id shouldBe entity.id
    }

    @Test
    fun `findById should return null when entity does not exist`() = runTest {
        val entity = createEntity()
        // Not inserted

        val found = idRepository.findById(entity.id)

        found shouldBe null
    }

    @Test
    fun `findById should return correct entity among multiple`() = runTest {
        val entities = List(5) { createEntity() }
        crudRepository.insert(entities)

        val target = entities[2]
        val found = idRepository.findById(target.id)

        found shouldNotBe null
        found!!.id shouldBe target.id
    }

    // ========== DeleteById Tests ==========

    @Test
    fun `deleteById should remove existing entity`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        val deleted = idRepository.deleteById(entity.id)

        deleted shouldBe 1
        idRepository.findById(entity.id) shouldBe null
    }

    @Test
    fun `deleteById should return 0 when entity does not exist`() = runTest {
        val entity = createEntity()
        // Not inserted

        val deleted = idRepository.deleteById(entity.id)

        deleted shouldBe 0
    }

    @Test
    fun `deleteById should only delete specified entity`() = runTest {
        val entities = List(5) { createEntity() }
        crudRepository.insert(entities)

        val target = entities[2]
        idRepository.deleteById(target.id)

        // Target should be gone
        idRepository.findById(target.id) shouldBe null

        // Others should remain
        entities.filterNot { it.id == target.id }.forEach { entity ->
            idRepository.findById(entity.id) shouldNotBe null
        }
    }
}
