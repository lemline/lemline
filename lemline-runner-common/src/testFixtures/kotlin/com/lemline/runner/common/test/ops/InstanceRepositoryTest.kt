// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test.ops

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithInstanceRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Concrete test class for instance-based repository operations.
 * Tests findByWorkflowId method.
 *
 * Use as a @Nested inner class in concrete repository tests.
 *
 * @param T The entity type being tested (must implement WithInstanceMessage)
 * @param instanceRepository Provider for instance operations (uses lazy eval to handle @Inject timing)
 * @param crudRepository Provider for CRUD operations needed for setup
 * @param createEntity Factory function to create random test entities
 * @param getWorkflowId Function to extract the workflow ID from an entity
 */
@ExperimentalTime
abstract class InstanceRepositoryTest<T : WithInstanceMessage>(
    instanceRepository: () -> WithInstanceRepository<T>,
    crudRepository: () -> WithCrudRepository<T>,
    private val createEntity: () -> T,
    private val getWorkflowId: (T) -> WorkflowId
) {
    private val instanceRepository: WithInstanceRepository<T> by lazy { instanceRepository() }
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }

    @BeforeEach
    fun setupInstanceTest() = runTest {
        crudRepository.deleteAll()
    }

    // ========== FindByWorkflowId Tests ==========

    @Test
    fun `findByWorkflowId should return entity when it exists`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        val found = instanceRepository.findByWorkflowId(getWorkflowId(entity))

        found shouldNotBe null
        getWorkflowId(found!!) shouldBe getWorkflowId(entity)
    }

    @Test
    fun `findByWorkflowId should return null when entity does not exist`() = runTest {
        val entity = createEntity()
        // Not inserted

        val found = instanceRepository.findByWorkflowId(getWorkflowId(entity))

        found shouldBe null
    }

    @Test
    fun `findByWorkflowId should return null for non-matching workflow ID`() = runTest {
        val entity = createEntity()
        crudRepository.insert(entity)

        // Create a different entity to get a different workflow ID
        val otherEntity = createEntity()

        val found = instanceRepository.findByWorkflowId(getWorkflowId(otherEntity))

        found shouldBe null
    }

    @Test
    fun `findByWorkflowId should return correct entity among multiple`() = runTest {
        val entities = List(5) { createEntity() }
        crudRepository.insert(entities)

        val target = entities[2]
        val found = instanceRepository.findByWorkflowId(getWorkflowId(target))

        found shouldNotBe null
        getWorkflowId(found!!) shouldBe getWorkflowId(target)
    }
}
