// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.H2TestDatabaseConfig
import io.cloudevents.jackson.JsonFormat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Creation events are persisted with the accepted command and relayed independently of it:
 * they survive a crash after the commit, they appear without any worker, and a failed
 * publication is retried, never recorded as delivered.
 */
class CreationEventOutboxTest {

    /** A broker that can be made unavailable. */
    private class FakePublisher : LifecycleEventPublisher {
        override var enabled = true
        var failing = false
        val published = CopyOnWriteArrayList<String>()
        override suspend fun publish(payload: String) {
            if (failing) error("lifecycle channel unavailable")
            published.add(payload)
        }
    }

    private val publisher = FakePublisher()
    private val repository = CreationEventRepository().apply { databaseConfig = testDb }
    private val enqueuer = CreationEventEnqueuer().also {
        it.repository = repository
        it.publisher = publisher
    }

    private fun relay(maxAttempts: Int = 3) = CreationEventOutbox().also {
        it.databaseConfig = testDb
        it.creationEventRepository = repository
        it.publisher = publisher
        it.commandEmitter = mockk(relaxed = true)
        it.creationEventOutboxConfig = object : CreationEventOutboxConfig {
            override val enabled = true
            override val outbox = object : OutboxConfig {
                override val every: Duration = 1.hours
                override val batchSize = 100
                override val initialJitter: Duration = Duration.ZERO
                override val retryDelay: Duration = 1.milliseconds
                override val maxAttempts = maxAttempts
            }
            override val cleanup = object : CleanupConfig {
                override val every: Duration = 1.hours
                override val initialJitter: Duration = Duration.ZERO
                override val after: Duration = 1.hours
                override val batchSize = 100
            }
        }
    }

    private val info = WorkflowInfo(WorkflowNamespace("ns"), WorkflowName("wf"), WorkflowVersion("1.0.0"))
    private val createdAt = Instant.parse("2026-03-04T05:06:07.123456Z")

    private fun acceptedStart(id: WorkflowId = WorkflowId.random()) = InstanceMessage(
        workflowInfo = info,
        workflowState = StepByStepOrchestrator.initCmd(id, buildJsonObject { put("a", 1) }, createdAt = createdAt),
    )

    @BeforeEach
    fun clear() = runTest {
        repository.deleteAll()
        publisher.published.clear()
        publisher.failing = false
        publisher.enabled = true
    }

    @Test
    fun `rolled back acceptance leaves no creation event`() = runTest {
        val start = acceptedStart()
        assertThrows<IllegalStateException> {
            testDb.withTransaction { conn ->
                enqueuer.enqueueCreation(start, conn)
                error("command insert failed")
            }
        }
        repository.findByWorkflowId(start.workflowState.workflowId).shouldBeEmpty()
    }

    @Test
    fun `committed creation events are published by the scheduled relay after a crash, without any worker`() = runTest {
        val start = acceptedStart()
        // The accepting process commits, then dies before its immediate relay: nothing consumes
        // the command either. Another process's scheduled relay recovers the events.
        val ids = testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }
        ids shouldHaveSize 2
        publisher.published.shouldBeEmpty()

        relay().processEntities(100, 3, 1.milliseconds)

        val events = publisher.published.map { JsonFormat().deserialize(it.toByteArray()) }
        events.map { it.type }.toSet() shouldBe setOf(LifecycleEventType.WORKFLOW_CREATED.type, LifecycleEventType.TASK_CREATED.type)
        events.map { it.id }.toSet() shouldBe ids.map { it.toString() }.toSet()
        for (event in events) event.time!!.toInstant().toKotlinInstant() shouldBe createdAt
        val task = events.single { it.type.contains("task.created") }
        parseJson(String(task.data!!.toBytes())).jsonObject.getValue("task").jsonPrimitive.content shouldBe NodePosition.doRoot.toString()
        repository.findByWorkflowId(start.workflowState.workflowId).forEach {
            it.outboxCompletedAt shouldNotBe null
            it.cleanupAfter shouldNotBe null // eligible for retention cleanup
        }
    }

    @Test
    fun `an unavailable lifecycle channel is retried with the same frozen event, never marked delivered`() = runTest {
        val start = acceptedStart()
        val ids = testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }
        val frozen = repository.findByWorkflowId(start.workflowState.workflowId).associate { it.id to it.eventPayload }

        publisher.failing = true
        relay().relayNow(ids) shouldBe 0
        repository.findByWorkflowId(start.workflowState.workflowId).forEach { row ->
            row.outboxCompletedAt shouldBe null
            row.outboxFailedAt shouldBe null
            row.outboxAttemptCount shouldBe 1
            row.outboxErrorMessage shouldBe "lifecycle channel unavailable"
        }

        publisher.failing = false
        Thread.sleep(150) // past the (≥100 ms) backoff
        relay().processEntities(100, 3, 1.milliseconds)
        publisher.published shouldHaveSize 2
        // Retries publish the payload frozen at acceptance: same IDs, same times.
        publisher.published.toSet() shouldBe frozen.values.toSet()
        repository.findByWorkflowId(start.workflowState.workflowId).forEach { it.outboxCompletedAt shouldNotBe null }
    }

    @Test
    fun `exhausted publication attempts are recorded as failed, not delivered`() = runTest {
        val start = acceptedStart()
        val ids = testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }
        publisher.failing = true
        relay(maxAttempts = 1).relayNow(ids)
        repository.findByWorkflowId(start.workflowState.workflowId).forEach { row ->
            row.outboxCompletedAt shouldBe null
            row.outboxFailedAt shouldNotBe null
            row.cleanupAfter shouldBe null // kept for inspection and manual requeue
        }
    }

    @Test
    fun `duplicate acceptance enqueues each creation event once`() = runTest {
        val start = acceptedStart()
        val first = testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }
        // e.g. a redelivered acceptance, re-derived with a later clock reading
        val again = start.copy(workflowState = start.workflowState.copy(createdAt = createdAt + 5.seconds))
        val second = testDb.withTransaction { conn -> enqueuer.enqueueCreation(again, conn) }
        second shouldBe first
        val rows = repository.findByWorkflowId(start.workflowState.workflowId)
        rows shouldHaveSize 2
        rows.forEach { it.eventTime shouldBe createdAt }
    }

    @Test
    fun `immediate relay publishes committed events at once`() = runTest {
        val start = acceptedStart()
        val ids = testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }
        relay().relayNow(ids) shouldBe 2
        relay().relayNow(ids) shouldBe 0 // already delivered
        publisher.published shouldHaveSize 2
    }

    @Test
    fun `nothing is enqueued when the creation event outbox is disabled`() = runTest {
        enqueuer.outboxConfig = relay().creationEventOutboxConfig.let { base ->
            object : CreationEventOutboxConfig by base { override val enabled = false }
        }
        val start = acceptedStart()
        testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }.shouldBeEmpty()
        repository.findByWorkflowId(start.workflowState.workflowId).shouldBeEmpty()
        enqueuer.outboxConfig = null
    }

    @Test
    fun `nothing is enqueued when lifecycle events are disabled`() = runTest {
        publisher.enabled = false
        val start = acceptedStart()
        testDb.withTransaction { conn -> enqueuer.enqueueCreation(start, conn) }.shouldBeEmpty()
        repository.findByWorkflowId(start.workflowState.workflowId).shouldBeEmpty()
    }

    @Test
    fun `fork branch task created is enqueued with the given creation time`() = runTest {
        val start = acceptedStart()
        val branchCreatedAt = Clock.System.now()
        val ids = testDb.withTransaction { conn ->
            enqueuer.enqueueTaskCreated(info, start.workflowState.nodeStack, NodePosition("/do/0/fork/fork/branches/0/a"),
                buildJsonObject {}, branchCreatedAt, conn)
        }
        ids shouldHaveSize 1
        relay().relayNow(ids) shouldBe 1
        val event = JsonFormat().deserialize(publisher.published.single().toByteArray())
        event.type shouldBe LifecycleEventType.TASK_CREATED.type
        event.time!!.toInstant().toKotlinInstant() shouldBe branchCreatedAt
    }

    companion object {
        private val testDb = H2TestDatabaseConfig().also { it.migrate() }

        @AfterAll
        @JvmStatic
        fun teardown() = testDb.close()
    }
}
