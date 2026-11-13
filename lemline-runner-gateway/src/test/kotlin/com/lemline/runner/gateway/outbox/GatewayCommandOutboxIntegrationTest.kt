// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.values.IDV7
import com.lemline.core.random.randomResumeFromTaskCommand
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.schedules.ScheduleRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Integration test validating the transactional atomicity between
 * schedule insert and outbox insert, as used by InstanceStartService.
 */
@DisplayName("Gateway Command Outbox Integration")
class GatewayCommandOutboxIntegrationTest {

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val outboxRepository: GatewayCommandRepository by lazy {
            GatewayCommandRepository().apply { databaseConfig = testDb }
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
        val outboxEntry = GatewayCommandModel(
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

        val outboxEntry = GatewayCommandModel(
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

        val outboxEntry = GatewayCommandModel(
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
        val outboxEntry = GatewayCommandModel(
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

    @Test
    fun `immediate relay sends and completes the committed command`() = runBlocking {
        val entry = newCommand()
        outboxRepository.insert(entry)
        val emitter = mockk<CommandEmitter>()
        coEvery { emitter.send(any(), entry.id) } returns Unit

        immediateOutbox(emitter).processNow(entry.id)

        withTimeout(5_000) {
            while (outboxRepository.findById(entry.id)?.outboxCompletedAt == null) delay(10)
        }
        coVerify(exactly = 1) { emitter.send(any(), entry.id) }
        val stored = outboxRepository.findById(entry.id)!!
        stored.outboxCompletedAt shouldNotBe null
        stored.outboxAttemptCount shouldBe 1
        outboxRepository.findEntitiesToProcess(5, 10, null).size shouldBe 0
        Unit
    }

    @Test
    fun `immediate send failure leaves the command for scheduled retry`() = runBlocking {
        val entry = newCommand()
        outboxRepository.insert(entry)
        val emitter = mockk<CommandEmitter>()
        coEvery { emitter.send(any(), entry.id) } throws IllegalStateException("broker unavailable")

        immediateOutbox(emitter).processNow(entry.id)

        withTimeout(5_000) {
            while (outboxRepository.findById(entry.id)?.outboxAttemptCount != 1) delay(10)
        }
        coVerify(exactly = 1) { emitter.send(any(), entry.id) }
        val stored = outboxRepository.findById(entry.id)!!
        stored.outboxCompletedAt shouldBe null
        stored.outboxFailedAt shouldBe null
        stored.outboxAttemptCount shouldBe 1
        stored.outboxDelayedUntil shouldNotBe null
        Unit
    }

    @Test
    fun `disabled gateway outbox leaves the command for recovery`() = runBlocking {
        val entry = newCommand()
        outboxRepository.insert(entry)
        val emitter = mockk<CommandEmitter>()

        immediateOutbox(emitter, relayEnabled = false).processNow(entry.id)

        coVerify(exactly = 0) { emitter.send(any(), entry.id) }
        outboxRepository.findById(entry.id)!!.outboxCompletedAt shouldBe null
        Unit
    }

    private fun newCommand() = GatewayCommandModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomResumeFromTaskCommand(),
        ),
        outboxScheduledFor = Clock.System.now(),
    )

    private fun immediateOutbox(emitter: CommandEmitter, relayEnabled: Boolean = true) = GatewayCommandOutbox().apply {
        GatewayCommandOutbox::class.java.getDeclaredField("databaseConfig").apply { isAccessible = true }
            .set(this, testDb)
        gatewayCommandRepository = outboxRepository
        GatewayCommandOutbox::class.java.getDeclaredField("commandEmitter").apply { isAccessible = true }
            .set(this, emitter)
        gatewayCommandConfig = mockk {
            io.mockk.every { enabled } returns relayEnabled
            io.mockk.every { outbox } returns object : OutboxConfig {
                override val every = 400.milliseconds
                override val batchSize = 100
                override val initialJitter = 0.milliseconds
                override val retryDelay = 5.seconds
                override val maxAttempts = 5
            }
        }
    }
}
