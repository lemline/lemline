// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.H2TestDatabaseConfig
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScheduleOutbox [H2]")
class ScheduleOutboxH2Test {

    private val sent = mutableListOf<InstanceMessage<out WorkflowCommand>>()
    private val commandEmitter = object : CommandEmitter {
        override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
            sent += message
        }
    }
    private val lifecycleHook = mockk<LifecycleEventHook>()

    private val outbox = ScheduleOutbox().apply {
        lifecycleHook = this@ScheduleOutboxH2Test.lifecycleHook
        scheduleRepository = repository
        // Protected injection points, set the way CDI does: directly on the field.
        inject("commandEmitter", this@ScheduleOutboxH2Test.commandEmitter)
        inject("databaseConfig", testDb)
    }

    private fun ScheduleOutbox.inject(field: String, value: Any) {
        ScheduleOutbox::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }

    @Test
    fun `a scheduled execution is announced as created, with its own id and start time`() = runTest {
        val registeredAt = Instant.parse("2026-01-01T00:00:00Z")
        val workflowInfo = randomWorkflowInfo()
        val schedule = ScheduleModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = StepByStepOrchestrator.initCmd(startedAt = registeredAt),
            ),
            outboxScheduledFor = Clock.System.now() - 1.minutes,
            scheduleAfter = null,
            scheduleEvery = null,
            scheduleCron = "* * * * *",
            scheduleZone = null,
        )
        val registeredWorkflowId = schedule.instanceMessage.workflowState.nodeStack.rootState.workflowId
        repository.insert(schedule)

        val announcedInfo = slot<WorkflowInfo>()
        val announced = slot<NodeStack>()
        coEvery { lifecycleHook.onWorkflowCreated(capture(announcedInfo), capture(announced)) } just Runs

        val beforeTick = Clock.System.now()
        outbox.processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)

        val sentRoot = sent.single().workflowState.nodeStack.rootState
        sentRoot.workflowId shouldNotBe registeredWorkflowId
        sentRoot.startedAt shouldBeGreaterThanOrEqualTo beforeTick
        announcedInfo.captured shouldBe workflowInfo
        announced.captured.rootState shouldBe sentRoot
    }

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: ScheduleRepository by lazy {
            ScheduleRepository().apply {
                databaseConfig = testDb
            }
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
}
