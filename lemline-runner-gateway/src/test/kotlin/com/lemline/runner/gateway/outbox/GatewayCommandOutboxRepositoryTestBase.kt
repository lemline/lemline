// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.junit.jupiter.api.Nested

abstract class GatewayCommandOutboxRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): GatewayCommandOutboxRepository

    private fun createEntity() = randomGatewayCommandOutboxModel()
    private fun modifyEntity(entity: GatewayCommandOutboxModel): GatewayCommandOutboxModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<GatewayCommandOutboxModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<GatewayCommandOutboxModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<GatewayCommandOutboxModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<GatewayCommandOutboxModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )
}
