// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.common.test.OrchestrationTestDatabase
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import com.lemline.runner.common.test.outbox.OutboxProcessorTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeAll

abstract class GatewayCommandOutboxProcessorTestBase(
    private val testDb: OrchestrationTestDatabase,
    repository: GatewayCommandRepository = GatewayCommandRepository().apply { databaseConfig = testDb },
) : OutboxProcessorTest<GatewayCommandModel>(
    outboxRepository = { repository },
    crudRepository = { repository },
    idRepository = { repository },
    databaseConfig = { testDb },
    modelClass = GatewayCommandModel::class,
    createTestModel = { _ ->
        GatewayCommandModel(
            id = com.lemline.common.values.IDV7.random(),
            instanceMessage = com.lemline.runner.common.messaging.InstanceMessage(
                workflowInfo = com.lemline.core.random.randomWorkflowInfo(),
                workflowState = com.lemline.core.random.randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = kotlin.time.Clock.System.now(),
        )
    }
)

@DisplayName("GatewayCommandOutbox Processor [H2]")
class GatewayCommandOutboxProcessorTest : GatewayCommandOutboxProcessorTestBase(database) {
    companion object {
        private val database = H2TestDatabaseConfig()

        @BeforeAll
        @JvmStatic
        fun initDb() = database.migrate()

        @AfterAll
        @JvmStatic
        fun teardown() = database.close()
    }
}

@DisplayName("GatewayCommandOutbox Processor [SQLite]")
class GatewayCommandOutboxProcessorSQLiteTest : GatewayCommandOutboxProcessorTestBase(database) {
    companion object {
        private val database = SQLiteTestDatabaseConfig()

        @BeforeAll
        @JvmStatic
        fun initDb() = database.migrate()

        @AfterAll
        @JvmStatic
        fun teardown() = database.close()
    }
}
