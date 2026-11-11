// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.common.values.IDV7
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class ScheduleRecurrenceTestBase {
    protected abstract fun database(): DatabaseConfig
    protected abstract fun repository(): ScheduleRepository

    private val sent = mutableListOf<Pair<InstanceMessage<out WorkflowCommand>, IDV7?>>()
    private var failSend = false
    private val emitter = object : CommandEmitter {
        override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
            sent += message to idempotentKey
            if (failSend) error("Simulated broker failure")
        }
    }

    private fun outbox(repo: ScheduleRepository = repository(), hook: LifecycleEventHook = mockk(relaxed = true)) =
        ScheduleOutbox().apply {
            ScheduleOutbox::class.java.getDeclaredField("commandEmitter").apply { isAccessible = true }.set(this, emitter)
            ScheduleOutbox::class.java.getDeclaredField("databaseConfig").apply { isAccessible = true }.set(this, database())
            scheduleRepository = repo
            lifecycleHook = hook
        }

    private fun occurrence(after: Boolean = false, cron: Boolean = false) = ScheduleModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(randomWorkflowInfo(), StepByStepOrchestrator.initCmd()),
        outboxScheduledFor = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) - 10.minutes,
        scheduleAfter = if (after) "PT1M" else null,
        scheduleEvery = if (!after && !cron) "PT1M" else null,
        scheduleCron = if (cron) "* * * * *" else null,
        scheduleZone = null,
    )

    @BeforeEach
    fun clear() = runTest { repository().deleteAll() }

    private suspend fun tick(box: ScheduleOutbox) =
        box.processEntities(batchSize = 100, maxAttempts = 3, retryDelay = 1.seconds)

    private suspend fun assertRecurrence(cron: Boolean) {
        val first = occurrence(cron = cron)
        repository().insert(first)
        val box = outbox()
        repeat(3) { tick(box) }
        assertEquals(3, sent.size)
        assertEquals(3, sent.map { it.first.workflowId }.distinct().size)
        assertEquals(3, sent.map { it.second }.distinct().size)
        val rows = repository().listAll().sortedBy { it.outboxScheduledFor }
        assertEquals(4, rows.size)
        assertEquals(setOf(first.scheduleId), rows.map { it.scheduleId }.toSet())
        rows.zipWithNext().forEach { (current, next) ->
            assertTrue(next.outboxScheduledFor!! > current.outboxScheduledFor!!)
            assertEquals(current.nextOccurrenceId, next.id)
            assertNotNull(current.outboxCompletedAt)
            assertNotNull(current.cleanupAfter)
        }
        assertNull(rows.last().outboxCompletedAt)
        assertNull(rows.last().cleanupAfter)
        assertEquals(first.outboxScheduledFor, rows.first().outboxScheduledFor)
    }

    @Test
    fun `every creates a distinct row for each occurrence`() = runTest { assertRecurrence(false) }

    @Test
    fun `cron creates a distinct row for each occurrence`() = runTest { assertRecurrence(true) }

    @Test
    fun `publication retries keep occurrence identity and eventually stop`() = runTest {
        val first = occurrence()
        repository().insert(first)
        val box = outbox()
        failSend = true
        repeat(3) { attempt ->
            tick(box)
            val persisted = repository().findById(first.id)!!
            assertEquals(attempt + 1, persisted.outboxAttemptCount)
            assertEquals(first.outboxScheduledFor, persisted.outboxScheduledFor)
            assertNull(persisted.outboxCompletedAt)
            persisted.outboxDelayedUntil = Clock.System.now() - 1.seconds
            repository().update(persisted)
        }
        tick(box)
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { it.first.workflowId }.distinct().size)
        assertEquals(1, sent.map { it.second }.distinct().size)
        assertEquals(1, repository().countAll())
        assertNotNull(repository().findById(first.id)!!.outboxFailedAt)
    }

    @Test
    fun `lifecycle failure retries the same publication then creates one successor`() = runTest {
        val first = occurrence()
        repository().insert(first)
        var failHook = true
        val hook = mockk<LifecycleEventHook>()
        coEvery { hook.onWorkflowCreated(any(), any()) } answers {
            if (failHook) error("Simulated lifecycle failure")
        }
        val box = outbox(hook = hook)
        tick(box)
        assertEquals(1, repository().countAll())
        val pending = repository().findById(first.id)!!
        pending.outboxDelayedUntil = Clock.System.now() - 1.seconds
        repository().update(pending)
        failHook = false
        tick(box)
        assertEquals(2, sent.size)
        assertEquals(sent[0].second, sent[1].second)
        assertEquals(sent[0].first, sent[1].first)
        assertEquals(2, repository().countAll())
        assertNotNull(repository().findById(first.id)!!.outboxCompletedAt)
    }

    @Test
    fun `successor insert failure rolls back occurrence completion`() = runTest {
        val first = occurrence()
        repository().insert(first)
        val repo = spyk(repository())
        coEvery { repo.insert(match<ScheduleModel> { it.id == first.nextOccurrenceId }, any()) } throws
            IllegalStateException("Simulated insert failure")
        tick(outbox(repo))
        assertEquals(1, sent.size)
        assertNull(repository().findById(first.id)!!.outboxCompletedAt)
        assertEquals(1, repository().countAll())
        tick(outbox())
        assertEquals(sent[0].second, sent[1].second)
        assertEquals(2, repository().countAll())
    }

    @Test
    fun `after retains its occurrence until completion and ignores duplicate completions`() = runTest {
        val first = occurrence(after = true)
        repository().insert(first)
        val box = outbox()
        tick(box)
        val published = repository().findById(first.id)!!
        assertNotNull(published.outboxCompletedAt)
        assertNull(published.cleanupAfter)
        assertEquals(1, repository().countAll())
        val service = ScheduleService().apply { scheduleRepository = repository() }
        database().withTransaction { conn -> assertTrue(service.scheduleAfterCompletion(published.workflowId, conn)) }
        val next = repository().findById(first.nextOccurrenceId)!!
        assertEquals(first.scheduleId, next.scheduleId)
        assertNotEquals(published.workflowId, next.workflowId)
        assertTrue(next.outboxScheduledFor!! > Clock.System.now())
        database().withTransaction { conn -> assertEquals(false, service.scheduleAfterCompletion(published.workflowId, conn)) }
        assertEquals(2, repository().countAll())
        assertEquals(next.outboxScheduledFor, repository().findById(next.id)!!.outboxScheduledFor)
        // A delayed duplicate must not recreate a successor that has already been cleaned up.
        repository().delete(next)
        database().withTransaction { conn -> assertEquals(false, service.scheduleAfterCompletion(published.workflowId, conn)) }
        assertEquals(1, repository().countAll())
    }

    @Test
    fun `after first immediate run creates a scheduled occurrence and can recur again`() = runTest {
        val initial = occurrence(after = true).copy(outboxScheduledFor = null)
        repository().insert(initial)
        val service = ScheduleService().apply { scheduleRepository = repository() }
        database().withTransaction { conn -> service.scheduleAfterCompletion(initial.workflowId, conn) }
        val next = repository().findById(initial.nextOccurrenceId)!!
        next.outboxDelayedUntil = Clock.System.now() - 1.seconds
        repository().update(next)
        tick(outbox())
        val published = repository().findById(next.id)!!
        database().withTransaction { conn -> assertTrue(service.scheduleAfterCompletion(published.workflowId, conn)) }
        assertNotNull(repository().findById(next.nextOccurrenceId))
        assertEquals(3, repository().countAll())
    }
    @Test
    fun `concurrent completion deliveries create only one successor`() = runTest {
        val first = occurrence(after = true).copy(outboxScheduledFor = null)
        repository().insert(first)
        val service = ScheduleService().apply { scheduleRepository = repository() }
        val results = List(2) {
            async { database().withTransaction { conn -> service.scheduleAfterCompletion(first.workflowId, conn) } }
        }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(2, repository().countAll())
    }

    @Test
    fun `completion rollback preserves tracking and removes the uncommitted successor`() = runTest {
        val first = occurrence(after = true).copy(outboxScheduledFor = null)
        repository().insert(first)
        val service = ScheduleService().apply { scheduleRepository = repository() }
        assertFailsWith<IllegalStateException> {
            database().withTransaction { conn ->
                service.scheduleAfterCompletion(first.workflowId, conn)
                error("Simulated event transaction failure")
            }
        }
        assertNull(repository().findById(first.id)!!.cleanupAfter)
        assertEquals(1, repository().countAll())
        database().withTransaction { conn -> assertTrue(service.scheduleAfterCompletion(first.workflowId, conn)) }
        assertEquals(2, repository().countAll())
    }

    @Test
    fun `occurrence identity survives timestamp precision in persistence`() = runTest {
        val first = occurrence(after = true)
        val next = first.nextAfterCompletion(Instant.parse("2026-01-01T00:00:00.123456789Z"))
        repository().insert(next)
        val persisted = repository().findById(next.id)!!
        assertEquals(next.outboxScheduledFor, persisted.outboxScheduledFor)
        assertEquals(next.executionId, persisted.executionId)
        assertEquals(next.executionId, persisted.workflowId)
        assertEquals(next.messageId, persisted.messageId)
    }

}
