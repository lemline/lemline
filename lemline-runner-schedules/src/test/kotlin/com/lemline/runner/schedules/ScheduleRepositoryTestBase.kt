// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.schedules

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.InstanceRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Nested

abstract class ScheduleRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): ScheduleRepository

    private fun createEntity() = ScheduleModel.random()
    private fun modifyEntity(entity: ScheduleModel): ScheduleModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<ScheduleModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ScheduleModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class InstanceTests : InstanceRepositoryTest<ScheduleModel>(
        instanceRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getWorkflowId = { it.instanceMessage.workflowId }
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<ScheduleModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ScheduleModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

}
