// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.workflows.WorkflowCache
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.lifecycle.LifecycleEventPublisher
import com.lemline.runner.common.lifecycle.CreationEventOutbox
import com.lemline.runner.common.lifecycle.CreationEventOutboxConfig
import com.lemline.runner.common.lifecycle.CreationEventModel
import com.lemline.runner.common.lifecycle.CreationEventRepository
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.outbox.GatewayCommandOutbox
import com.lemline.runner.gateway.outbox.GatewayCommandRepository
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.sql.Connection
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A gateway start persists its creation events with the command. They are published even when
 * no worker runs, and the waiting interval they open closes only when a worker picks the command.
 */
@DisplayName("Gateway start creation events [H2]")
class InstanceStartCreationEventsTest {

    private class FakePublisher : LifecycleEventPublisher {
        override val enabled = true
        val published = CopyOnWriteArrayList<CloudEvent>()
        override suspend fun publish(payload: String) {
            published.add(JsonFormat().deserialize(payload.toByteArray()))
        }
    }

    private val publisher = FakePublisher()
    private val creationEventRepository = CreationEventRepository().apply { databaseConfig = testDb }
    private val commandRepository = GatewayCommandRepository().apply { databaseConfig = testDb }
    private val definitions = mockk<Definitions>()
    private val authorizer = mockk<GatewayAuthorizer>()
    private val principal = GatewayPrincipal(subject = "s", scopes = setOf("lemline.start"), namespaces = setOf("*"))

    /** Accepted 10 seconds before the worker below picks the command up. */
    private val acceptedAt = Clock.System.now() - 10.seconds

    private val creationEventOutbox = CreationEventOutbox().also {
        it.databaseConfig = testDb
        it.creationEventRepository = creationEventRepository
        it.publisher = publisher
        it.commandEmitter = mockk(relaxed = true)
        it.creationEventOutboxConfig = object : CreationEventOutboxConfig {
            override val enabled = true
            override val outbox = object : OutboxConfig {
                override val every: Duration = 1.hours
                override val batchSize = 100
                override val initialJitter: Duration = Duration.ZERO
                override val retryDelay: Duration = 1.milliseconds
                override val maxAttempts = 3
            }
            override val cleanup = object : CleanupConfig {
                override val every: Duration = 1.hours
                override val initialJitter: Duration = Duration.ZERO
                override val after: Duration = 1.hours
                override val batchSize = 100
            }
        }
    }

    private fun service(creationEventRepository: CreationEventRepository = this.creationEventRepository) = InstanceStartService().apply {
        starter = Starter().apply { definitions = this@InstanceStartCreationEventsTest.definitions }
        databaseConfig = testDb
        scheduleRepository = ScheduleRepository().apply { databaseConfig = testDb }
        gatewayCommandRepository = commandRepository
        // Workers stopped: nothing relays or consumes the command.
        gatewayCommandOutbox = mockk(relaxed = true)
        creationEventEnqueuer = CreationEventEnqueuer().also {
            it.repository = creationEventRepository
            it.publisher = publisher
        }
        // The immediate relay is not run here: the scheduled relay below must recover the rows.
        creationEventOutbox = mockk<CreationEventOutbox>(relaxed = true)
        authorizer = this@InstanceStartCreationEventsTest.authorizer
        namespaceActivityMonitor = mockk(relaxed = true)
        clock = object : Clock { override fun now() = acceptedAt }
    }

    private val request = StartInstanceRequest.newBuilder().setNamespace("team-a").setName("orders").build()

    @BeforeEach
    fun setUp() = runTest {
        WorkflowCache.clear()
        creationEventRepository.deleteAll()
        commandRepository.deleteAll()
        publisher.published.clear()
        every { authorizer.requireScope(principal, "lemline.start") } just runs
        every { authorizer.requireNamespace(principal, any()) } just runs
        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns WorkflowCache.parseYaml(
            """
            document: { dsl: '1.0.0', namespace: team-a, name: orders, version: '1.0.0' }
            do:
              - first: { set: { a: 1 } }
            """.trimIndent()
        )
    }

    @Test
    fun `creation events are observable with workers stopped and measure a delayed pickup`() = runTest {
        val result = service().start(request, principal)

        // Nothing executed the command, yet the scheduled relay publishes its creation events.
        creationEventOutbox.processEntities(100, 3, 1.milliseconds)
        val created = publisher.published.single { it.type == LifecycleEventType.WORKFLOW_CREATED.type }
        val doCreated = publisher.published.single { it.type == LifecycleEventType.TASK_CREATED.type }
        created.instant() shouldBe acceptedAt
        doCreated.instant() shouldBe acceptedAt
        val command = commandRepository.findByWorkflowId(result.instanceId)!!
        command.outboxCompletedAt shouldBe null
        command.instanceMessage.workflowState.createdAt shouldBe acceptedAt

        // A worker picks the command up 10 seconds after acceptance.
        val workerEvents = CopyOnWriteArrayList<CloudEvent>()
        val workflow = WorkflowCache.parseYamlAndPut(
            "document: { dsl: '1.0.0', namespace: team-a, name: orders, version: '1.0.0' }\ndo:\n  - first: { set: { a: 1 } }"
        )
        StepByStepOrchestrator.runByTask(workflow, command.instanceMessage.workflowState, command.workflowInfo,
            CloudEventLifecycleHook(object : LifecycleEventEmitter {
                override suspend fun emit(cloudEvent: CloudEvent) { workerEvents.add(cloudEvent) }
            }))
        val started = workerEvents.single { it.type == LifecycleEventType.WORKFLOW_STARTED.type }.instant()
        val doStarted = workerEvents.single { it.type == LifecycleEventType.TASK_STARTED.type }
        (started - acceptedAt >= 10.seconds) shouldBe true
        (doStarted.instant() - acceptedAt >= 10.seconds) shouldBe true
        // The worker no longer emits /do task.created; the relayed one has the identity it would derive.
        workerEvents.filter { it.type == LifecycleEventType.TASK_CREATED.type && String(it.data!!.toBytes()).contains("\"task\":\"/do\"") }
            .shouldBeEmpty()
        doCreated.getExtension("lemlineworkflowid") shouldBe result.instanceId.toString()
    }

    @Test
    fun `duplicate start enqueues no second set of creation events`() = runTest {
        val withId = request.toBuilder().setInstanceId("018f40c4-5f0f-7bf2-b0f7-4a4931227c31").build()
        val first = service().start(withId, principal)
        val second = service().start(withId, principal)
        second.result shouldBe StartInstanceOutcome.ACCEPTED_EXISTING
        creationEventRepository.findByWorkflowId(first.instanceId).size shouldBe 2
    }

    @Test
    fun `a failed acceptance rolls back the command and its creation events together`() = runTest {
        val failing = object : CreationEventRepository() {
            override suspend fun insert(entities: List<CreationEventModel>, connection: Connection?): Int =
                error("database unavailable")
        }.apply { databaseConfig = testDb }
        val withId = request.toBuilder().setInstanceId("018f40c4-5f0f-7bf2-b0f7-4a4931227c32").build()

        shouldThrow<IllegalStateException> { service(failing).start(withId, principal); Unit }

        val instanceId = WorkflowId(IDV7.from(withId.instanceId))
        commandRepository.findByWorkflowId(instanceId) shouldBe null
        creationEventRepository.findByWorkflowId(instanceId).shouldBeEmpty()
    }

    @Test
    fun `relay retries keep the acceptance time`() = runTest {
        val result = service().start(request, principal)
        val rows = creationEventRepository.findByWorkflowId(result.instanceId)
        creationEventOutbox.processEntities(100, 3, 1.milliseconds)
        creationEventRepository.findByWorkflowId(result.instanceId).forEach { it.outboxCompletedAt shouldNotBe null }
        // Published events are the ones frozen at acceptance (IDs and full-precision times).
        publisher.published.map { it.id }.toSet() shouldBe rows.map { it.id.toString() }.toSet()
        publisher.published.forEach { it.instant() shouldBe acceptedAt }
    }

    private fun CloudEvent.instant(): Instant = requireNotNull(time).toInstant().toKotlinInstant()

    companion object {
        private val testDb = H2TestDatabaseConfig().also { it.migrate() }

        @AfterAll
        @JvmStatic
        fun teardown() = testDb.close()
    }
}
