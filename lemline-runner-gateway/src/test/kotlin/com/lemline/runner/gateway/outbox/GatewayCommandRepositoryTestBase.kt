// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

abstract class GatewayCommandRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): GatewayCommandRepository

    private fun createEntity() = randomGatewayCommandOutboxModel()
    private fun modifyEntity(entity: GatewayCommandModel): GatewayCommandModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<GatewayCommandModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<GatewayCommandModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<GatewayCommandModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class ImmediateClaimTests {

        @BeforeEach
        fun clear() = runTest { getRepository().deleteAll() }

        @Test
        fun `claims only a ready unfinished command by id`() = runTest {
            val ready = GatewayCommandModel(
                id = com.lemline.common.values.IDV7.random(),
                instanceMessage = createEntity().instanceMessage,
                outboxScheduledFor = Clock.System.now(),
            )
            getRepository().insert(ready)

            getDatabaseConfig().withTransaction { conn ->
                getRepository().findReadyById(ready.id, 5, conn)?.id shouldBe ready.id
            }

            ready.outboxCompletedAt = Clock.System.now()
            getRepository().update(ready)
            getDatabaseConfig().withTransaction { conn ->
                getRepository().findReadyById(ready.id, 5, conn) shouldBe null
            }
        }

        @Test
        fun `does not claim a delayed or exhausted command`() = runTest {
            val delayed = GatewayCommandModel(
                id = com.lemline.common.values.IDV7.random(),
                instanceMessage = createEntity().instanceMessage,
                outboxScheduledFor = Clock.System.now() + 1.days,
            )
            val exhausted = GatewayCommandModel(
                id = com.lemline.common.values.IDV7.random(),
                instanceMessage = createEntity().instanceMessage,
                outboxScheduledFor = Clock.System.now(),
            ).apply { outboxAttemptCount = 5 }
            getRepository().insert(delayed)
            getRepository().insert(exhausted)

            getDatabaseConfig().withTransaction { conn ->
                getRepository().findReadyById(delayed.id, 5, conn) shouldBe null
                getRepository().findReadyById(exhausted.id, 5, conn) shouldBe null
            }
        }
    }

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<GatewayCommandModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )
}
