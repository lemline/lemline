// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WaitModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.repositories.bases.ops.CleanerRepositoryTest
import com.lemline.runner.repositories.bases.ops.CrudRepositoryTest
import com.lemline.runner.repositories.bases.ops.IdRepositoryTest
import com.lemline.runner.repositories.bases.ops.InstanceRepositoryTest
import com.lemline.runner.repositories.bases.ops.OutboxRepositoryTest
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Nested

/**
 * Abstract base class for wait repository tests.
 * Uses composition pattern with @Nested inner classes to run all test suites.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class WaitRepositoryTest {

    @Inject
    lateinit var repository: WaitRepository

    @Inject
    lateinit var databaseManager: DatabaseManager

    // Shared entity factory and modifier
    private fun createEntity() = WaitModel.random()
    private fun modifyEntity(entity: WaitModel) = entity.copy().apply { outboxDelayedUntil = Instant.random() }

    @Nested
    inner class CrudTests : CrudRepositoryTest<WaitModel>(
        crudRepository = { repository },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<WaitModel>(
        idRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity
    )

    @Nested
    inner class InstanceTests : InstanceRepositoryTest<WaitModel>(
        instanceRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getWorkflowId = { it.instanceMessage.workflowId }
    )
    
    @Nested
    inner class OutboxTests : OutboxRepositoryTest<WaitModel>(
        outboxRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<WaitModel>(
        cleanerRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )
}
