// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.InstanceRepositoryTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

abstract class ParentRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): ParentRepository

    private fun createEntity() = ParentModel.random()
    private fun modifyEntity(entity: ParentModel): ParentModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { completedAt = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<ParentModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ParentModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class InstanceTests : InstanceRepositoryTest<ParentModel>(
        instanceRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getWorkflowId = { it.instanceMessage.workflowId }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ParentModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    // ========== findByChildId Tests ==========

    @Test
    fun `findByChildId should return parent when child exists`() = runTest {
        getRepository().deleteAll()
        val parent = ParentModel.random()
        getRepository().insert(parent)

        val found = getRepository().findByChildId(parent.childId)

        found shouldNotBe null
        found!!.id shouldBe parent.id
        found.childId shouldBe parent.childId
    }

    @Test
    fun `findByChildId should return null when child does not exist`() = runTest {
        getRepository().deleteAll()
        val parent = ParentModel.random()
        getRepository().insert(parent)

        val nonExistentChildId = WorkflowId.random()
        val found = getRepository().findByChildId(nonExistentChildId)

        found shouldBe null
    }

    @Test
    fun `findByChildId should return correct parent among multiple`() = runTest {
        getRepository().deleteAll()
        val parents = List(5) { ParentModel.random() }
        getRepository().insert(parents)

        val target = parents[2]
        val found = getRepository().findByChildId(target.childId)

        found shouldNotBe null
        found!!.id shouldBe target.id
        found.childId shouldBe target.childId
    }

}
