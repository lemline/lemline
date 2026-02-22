// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.common.test.outbox.OutboxProcessorTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("GatewayCommandOutbox Processor [H2]")
class GatewayCommandOutboxProcessorTest : OutboxProcessorTest<GatewayCommandOutboxModel>(
    outboxRepository = { repository },
    crudRepository = { repository },
    idRepository = { repository },
    databaseConfig = { testDb },
    modelClass = GatewayCommandOutboxModel::class,
    createTestModel = { _ ->
        GatewayCommandOutboxModel(
            id = com.lemline.common.values.IDV7.random(),
            instanceMessage = com.lemline.runner.common.messaging.InstanceMessage(
                workflowInfo = com.lemline.core.random.randomWorkflowInfo(),
                workflowState = com.lemline.core.random.randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = kotlin.time.Clock.System.now(),
        )
    }
) {
    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: GatewayCommandOutboxRepository by lazy {
            GatewayCommandOutboxRepository().apply {
                databaseConfig = testDb
            }
        }

        @BeforeAll
        @JvmStatic
        fun initDb() {
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDb.close()
        }
    }
}
