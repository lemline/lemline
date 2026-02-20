// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.common.values.IDV7
import com.lemline.core.random.randomTaskRetryScheduledEvent
import com.lemline.core.random.randomWorkflowInfo
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
import org.junit.jupiter.api.Nested

/**
 * Abstract base class for RetryRepository tests.
 * Provides common test infrastructure for testing against different database types.
 */
abstract class RetryRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): RetryRepository

    private fun createEntity() = RetryModel.random()
    private fun modifyEntity(entity: RetryModel): RetryModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<RetryModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<RetryModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<RetryModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<RetryModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class OutboxProcessorTests : OutboxProcessorTest<RetryModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        idRepository = { getRepository() },
        databaseConfig = { getDatabaseConfig() },
        modelClass = RetryModel::class,
        createTestModel = { _ ->
            RetryModel(
                id = IDV7.random(),
                instanceMessage = InstanceMessage(
                    workflowInfo = randomWorkflowInfo(),
                    workflowState = randomTaskRetryScheduledEvent(),
                ),
                outboxScheduledFor = Clock.System.now(),
                errorReason = "test-error-reason",
                errorClass = "test-error-class",
                errorMessage = "test-error-message",
                errorStackTrace = "test-error-stacktrace",
            )
        }
    )
}
