// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.lifecycle.LifecycleEventPublisher
import com.lemline.runner.common.lifecycle.CreationEventModel
import com.lemline.runner.common.lifecycle.CreationEventRepository
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.H2TestDatabaseConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScheduleOutbox [H2]")
class ScheduleOutboxH2Test {

    private val sent = mutableListOf<InstanceMessage<out WorkflowCommand>>()
    private var commandsFail = false
    private val commandEmitter = object : CommandEmitter {
        override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
            if (commandsFail) error("commands channel unavailable")
            sent += message
        }
    }

    /** Controllable clock: the acceptance time of due occurrences. */
    private var now: Instant = Clock.System.now()

    private val enabledPublisher = object : LifecycleEventPublisher {
        override val enabled = true
        override suspend fun publish(payload: String) = Unit
    }

    private fun outbox(creationEventRepository: CreationEventRepository = ScheduleOutboxH2Test.creationEventRepository) = ScheduleOutbox().apply {
        scheduleRepository = repository
        creationEventEnqueuer = CreationEventEnqueuer().also {
            it.repository = creationEventRepository
            it.publisher = enabledPublisher
        }
        clock = object : Clock { override fun now() = this@ScheduleOutboxH2Test.now }
        // Protected injection points, set the way CDI does: directly on the field.
        inject("commandEmitter", this@ScheduleOutboxH2Test.commandEmitter)
        inject("databaseConfig", testDb)
    }

    private fun ScheduleOutbox.inject(field: String, value: Any) {
        ScheduleOutbox::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }

    private val deadline = (Clock.System.now() - 1.minutes).atMicros()

    private fun cronOccurrence(registeredAt: Instant = Instant.parse("2026-01-01T00:00:00Z")) = ScheduleModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            // Registered, not yet accepted.
            workflowState = StepByStepOrchestrator.initCmd(startedAt = registeredAt).copy(createdAt = null),
        ),
        outboxScheduledFor = deadline,
        scheduleAfter = null,
        scheduleEvery = null,
        scheduleCron = "* * * * *",
        scheduleZone = null,
    )

    @BeforeEach
    fun clear() = runTest {
        repository.deleteAll()
        creationEventRepository.deleteAll()
        sent.clear()
        commandsFail = false
        now = Clock.System.now().atMicros()
    }

    @Test
    fun `a scheduled execution is accepted when due, with its own id, start and creation time`() = runTest {
        val schedule = cronOccurrence()
        val registeredWorkflowId = schedule.instanceMessage.workflowState.nodeStack.rootState.workflowId
        repository.insert(schedule)

        val beforeTick = now
        outbox().processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)

        val command = sent.single().workflowState as WorkflowCommand.ResumeFromTask
        val sentRoot = command.nodeStack.rootState
        sentRoot.workflowId shouldNotBe registeredWorkflowId
        sentRoot.startedAt shouldBeGreaterThanOrEqualTo beforeTick
        // Creation is the acceptance of the due occurrence: not its registration, not its deadline.
        command.createdAt shouldBe now
        (command.createdAt!! > deadline) shouldBe true

        val created = creationEventRepository.findByWorkflowId(sentRoot.workflowId)
        created.map { it.eventType }.toSet() shouldBe setOf(LifecycleEventType.WORKFLOW_CREATED.type, LifecycleEventType.TASK_CREATED.type)
        created.forEach { it.eventTime shouldBe now }

        // The successor occurrence is registered unaccepted: no creation time until it is due.
        repository.findById(schedule.nextOccurrenceId)!!.isAccepted shouldBe false
    }

    @Test
    fun `the creation time is frozen before publication and reused by publication retries`() = runTest {
        val schedule = cronOccurrence()
        repository.insert(schedule)
        val acceptedAt = now

        commandsFail = true
        outbox().processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)
        sent.shouldBeEmpty()
        val afterFailure = repository.findById(schedule.id)!!
        afterFailure.outboxCompletedAt shouldBe null
        afterFailure.instanceMessage.workflowState.createdAt shouldBe acceptedAt
        // A failed publication creates no lifecycle event: the occurrence is not dispatched yet.
        creationEventRepository.findByWorkflowId(afterFailure.workflowId).shouldBeEmpty()

        // Later relay pass, later clock: the occurrence keeps its creation time.
        commandsFail = false
        now = acceptedAt + 30.seconds
        repository.update(afterFailure.apply { outboxDelayedUntil = Clock.System.now() - 1.seconds })
        outbox().processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)

        val command = sent.single().workflowState as WorkflowCommand.ResumeFromTask
        command.createdAt shouldBe acceptedAt
        command.nodeStack.rootState.startedAt shouldBe afterFailure.instanceMessage.workflowState.nodeStack.rootState.startedAt
        creationEventRepository.findByWorkflowId(command.workflowId).forEach { it.eventTime shouldBe acceptedAt }
    }

    @Test
    fun `a crash after publication keeps the creation time for the republished command and its events`() = runTest {
        val schedule = cronOccurrence()
        repository.insert(schedule)
        val acceptedAt = now

        // The command is published, then the batch fails before committing (here: its creation
        // events cannot be written), so it rolls back as if the process had crashed.
        val broken = object : CreationEventRepository() {
            override suspend fun insert(entities: List<CreationEventModel>, connection: Connection?): Int =
                error("crash before commit")
        }.apply { databaseConfig = testDb }
        outbox(broken).processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)
        val first = sent.single().workflowState as WorkflowCommand.ResumeFromTask
        repository.findById(schedule.id)!!.outboxCompletedAt shouldBe null

        // Recovery republishes the same occurrence (same idempotency key), with the same frozen time.
        now = acceptedAt + 1.minutes
        outbox().processEntities(batchSize = 10, maxAttempts = 3, retryDelay = 1.seconds)
        val second = sent.last().workflowState as WorkflowCommand.ResumeFromTask
        first.createdAt shouldBe acceptedAt
        second.createdAt shouldBe acceptedAt
        second.workflowId shouldBe first.workflowId
        creationEventRepository.findByWorkflowId(WorkflowId(second.workflowId.value)).forEach { it.eventTime shouldBe acceptedAt }
        repository.findById(schedule.id)!!.outboxCompletedAt shouldNotBe null
    }

    @Test
    fun `every batch of a catch-up pass is published only after its creation time is committed`() = runTest {
        // More due occurrences than one batch, so the pass drains several batches.
        val schedules = List(5) { cronOccurrence() }
        schedules.forEach { repository.insert(it) }
        // Candidate rows: the registered occurrences and their overdue successors.
        val candidates = schedules.flatMap { s -> generateSequence(s.id) { it.derive("-next") }.take(4).toList() }

        val publishedBeforeCommit = mutableListOf<IDV7>()
        val checkingEmitter = object : CommandEmitter {
            override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
                // Read outside the publishing transaction: only committed state is visible.
                val committed = candidates.firstNotNullOfOrNull { id -> repository.findById(id)?.takeIf { it.messageId == idempotentKey } }
                val createdAt = (message.workflowState as WorkflowCommand.ResumeFromTask).createdAt
                if (committed?.instanceMessage?.workflowState?.createdAt != createdAt) publishedBeforeCommit += idempotentKey!!
                sent += message
            }
        }
        outbox().apply { inject("commandEmitter", checkingEmitter) }
            .processEntities(batchSize = 2, maxAttempts = 3, retryDelay = 1.seconds)

        (sent.size >= schedules.size) shouldBe true
        publishedBeforeCommit.shouldBeEmpty()
        sent.forEach { (it.workflowState as WorkflowCommand.ResumeFromTask).createdAt shouldNotBe null }
    }

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: ScheduleRepository by lazy {
            ScheduleRepository().apply {
                databaseConfig = testDb
            }
        }

        private val creationEventRepository: CreationEventRepository by lazy {
            CreationEventRepository().apply { databaseConfig = testDb }
        }

        private fun Instant.atMicros(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1_000 * 1_000)

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
