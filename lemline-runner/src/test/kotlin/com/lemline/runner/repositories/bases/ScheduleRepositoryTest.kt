// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ScheduleRepository
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
 * Abstract base class for schedule repository tests.
 * Uses composition pattern with @Nested inner classes to run all test suites.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ScheduleRepositoryTest {

    @Inject
    lateinit var repository: ScheduleRepository

    @Inject
    lateinit var databaseManager: DatabaseManager

    // Shared entity factory and modifier
    private fun createEntity() = ScheduleModel.random()
    private fun modifyEntity(entity: ScheduleModel) = entity.copy().apply { outboxDelayedUntil = Instant.random() }

    @Nested
    inner class CrudTests : CrudRepositoryTest<ScheduleModel>(
        crudRepository = { repository },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ScheduleModel>(
        idRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity
    )

    @Nested
    inner class InstanceTests : InstanceRepositoryTest<ScheduleModel>(
        instanceRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getWorkflowId = { it.instanceMessage.workflowId }
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<ScheduleModel>(
        outboxRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ScheduleModel>(
        cleanerRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )
}
