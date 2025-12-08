// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.common.values.WorkflowId
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ParentModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.bases.ops.CleanerRepositoryTest
import com.lemline.runner.repositories.bases.ops.CrudRepositoryTest
import com.lemline.runner.repositories.bases.ops.IdRepositoryTest
import com.lemline.runner.repositories.bases.ops.InstanceRepositoryTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Abstract base class for ParentWaitingRepository tests.
 * Tests basic CRUD operations for parent workflow waiting state.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ParentWaitingRepositoryTest {

    @Inject
    lateinit var repository: ParentRepository

    @Inject
    lateinit var databaseManager: DatabaseManager

    private fun createEntity() = ParentModel.random()
    private fun modifyEntity(entity: ParentModel) = entity.copy().apply { completedAt = Instant.random() }

    @Nested
    inner class CrudTests : CrudRepositoryTest<ParentModel>(
        crudRepository = { repository },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ParentModel>(
        idRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity
    )


    @Nested
    inner class InstanceTests : InstanceRepositoryTest<ParentModel>(
        instanceRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getWorkflowId = { it.instanceMessage.workflowId }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ParentModel>(
        cleanerRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )

    // ========== findByChildId Tests ==========

    @Test
    fun `findByChildId should return parent when child exists`() = runTest {
        val parent = ParentModel.random()
        repository.insert(parent)

        val found = repository.findByChildId(parent.childId)

        found shouldNotBe null
        found!!.id shouldBe parent.id
        found.childId shouldBe parent.childId
    }

    @Test
    fun `findByChildId should return null when child does not exist`() = runTest {
        val parent = ParentModel.random()
        repository.insert(parent)

        val nonExistentChildId = WorkflowId.random()
        val found = repository.findByChildId(nonExistentChildId)

        found shouldBe null
    }

    @Test
    fun `findByChildId should return correct parent among multiple`() = runTest {
        val parents = List(5) { ParentModel.random() }
        repository.insert(parents)

        val target = parents[2]
        val found = repository.findByChildId(target.childId)

        found shouldNotBe null
        found!!.id shouldBe target.id
        found.childId shouldBe target.childId
    }
}
