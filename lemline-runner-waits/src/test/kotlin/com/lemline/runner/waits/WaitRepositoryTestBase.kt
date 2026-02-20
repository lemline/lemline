// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.waits

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.*
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import com.lemline.runner.common.test.outbox.OutboxProcessorTest
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Nested

/**
 * Abstract base class for WaitRepository tests.
 * Provides common test infrastructure for testing against different database types.
 *
 * Subclasses must provide:
 * - A DatabaseConfig implementation
 * - A WaitRepository with the config injected
 */
abstract class WaitRepositoryTestBase {

    /**
     * Returns the database config for this test class.
     */
    protected abstract fun getDatabaseConfig(): DatabaseConfig

    /**
     * Returns the repository instance with the database config injected.
     */
    protected abstract fun getRepository(): WaitRepository

    // Shared entity factory and modifier
    private fun createEntity() = WaitModel.random()
    private fun modifyEntity(entity: WaitModel): WaitModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<WaitModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<WaitModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<WaitModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<WaitModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class OutboxProcessorTests : OutboxProcessorTest<WaitModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        idRepository = { getRepository() },
        databaseConfig = { getDatabaseConfig() },
        modelClass = WaitModel::class,
        createTestModel = { _: String ->
            WaitModel(
                id = IDV7.random(),
                instanceMessage = InstanceMessage(
                    workflowInfo = randomWorkflowInfo(),
                    workflowState = randomWaitStartedEvent(),
                ),
                outboxScheduledFor = Clock.System.now(),
            )
        }
    )

}
