// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.values.IDV7
import com.lemline.core.random.randomResumeFromTaskCommand
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.schedules.ScheduleRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Integration test validating the transactional atomicity between
 * schedule insert and outbox insert, as used by WorkflowStartService.
 */
@DisplayName("Gateway Command Outbox Integration")
class GatewayCommandOutboxIntegrationTest {

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val outboxRepository: GatewayCommandOutboxRepository by lazy {
            GatewayCommandOutboxRepository().apply { databaseConfig = testDb }
        }

        private val scheduleRepository: ScheduleRepository by lazy {
            ScheduleRepository().apply { databaseConfig = testDb }
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDb.close()
        }
    }

    @BeforeEach
    fun cleanUp() = runTest {
        outboxRepository.deleteAll()
        scheduleRepository.deleteAll()
    }

    @Test
    fun `outbox entry inserted within transaction is persisted`() = runTest {
        val msg = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomResumeFromTaskCommand(),
        )
        val outboxEntry = GatewayCommandOutboxModel(
            id = IDV7.random(),
            instanceMessage = msg,
            outboxScheduledFor = Clock.System.now(),
        )

        testDb.withTransaction { conn ->
            outboxRepository.insert(outboxEntry, conn)
        }

        val found = outboxRepository.findById(outboxEntry.id)
        found shouldNotBe null
        found!!.id shouldBe outboxEntry.id
        found.workflowId shouldBe msg.workflowId
    }

    @Test
    fun `transaction rollback discards both schedule and outbox entries`() = runTest {
        val scheduleModel = com.lemline.runner.schedules.ScheduleModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = Clock.System.now(),
            scheduleAfter = null,
            scheduleEvery = "PT1H",
            scheduleCron = null,
            scheduleZone = null,
        )

        val outboxEntry = GatewayCommandOutboxModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = Clock.System.now(),
        )

        try {
            testDb.withTransaction { conn ->
                scheduleRepository.insert(scheduleModel, conn)
                outboxRepository.insert(outboxEntry, conn)
                // Simulate failure after both inserts
                throw RuntimeException("Simulated crash")
            }
        } catch (_: RuntimeException) {
            // Expected
        }

        // Both should be rolled back
        scheduleRepository.countAll() shouldBe 0
        outboxRepository.countAll() shouldBe 0
    }

    @Test
    fun `transaction commit persists both schedule and outbox entries`() = runTest {
        val scheduleModel = com.lemline.runner.schedules.ScheduleModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = Clock.System.now(),
            scheduleAfter = null,
            scheduleEvery = "PT1H",
            scheduleCron = null,
            scheduleZone = null,
        )

        val outboxEntry = GatewayCommandOutboxModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = Clock.System.now(),
        )

        testDb.withTransaction { conn ->
            scheduleRepository.insert(scheduleModel, conn)
            outboxRepository.insert(outboxEntry, conn)
        }

        // Both should be committed
        scheduleRepository.countAll() shouldBe 1
        outboxRepository.countAll() shouldBe 1
    }

    @Test
    fun `outbox entry is found by outbox processor query after insert`() = runTest {
        val outboxEntry = GatewayCommandOutboxModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomResumeFromTaskCommand(),
            ),
            outboxScheduledFor = Clock.System.now(),
        )

        outboxRepository.insert(outboxEntry)

        val toProcess = outboxRepository.findEntitiesToProcess(
            maxAttempts = 5,
            limit = 10,
            connection = null
        )

        toProcess.size shouldBe 1
        toProcess[0].id shouldBe outboxEntry.id
    }
}
