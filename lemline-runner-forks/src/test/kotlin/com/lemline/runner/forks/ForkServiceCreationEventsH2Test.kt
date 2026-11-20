// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.common.values.IDV7
import com.lemline.common.values.info
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.lifecycle.LifecycleEventPublisher
import com.lemline.runner.common.lifecycle.CreationEventOutbox
import com.lemline.runner.common.lifecycle.CreationEventRepository
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.H2TestDatabaseConfig
import io.cloudevents.CloudEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The production fork path (events channel, ForkService), not the in-memory FullOrchestrator:
 * each branch is a new task invocation, created when the fork is accepted.
 */
@DisplayName("ForkService creation events [H2]")
class ForkServiceCreationEventsH2Test {

    private val yaml = """
        document: { dsl: '1.0.0', namespace: forks, name: branches, version: '1.0.0' }
        do:
          - split:
              fork:
                branches:
                  - left: { set: { side: left } }
                  - right: { set: { side: right } }
    """.trimIndent()

    private val sent = CopyOnWriteArrayList<InstanceMessage<out WorkflowCommand>>()
    private val emitter = object : CommandEmitter {
        override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
            sent.add(message)
        }
    }
    private val creationEventRepository = CreationEventRepository().apply { databaseConfig = testDb }
    private val creationEventOutbox = mockk<CreationEventOutbox>(relaxed = true)

    /** The fork is accepted 5 seconds before a worker picks up its branches. */
    private val acceptedAt = Clock.System.now() - 5.seconds

    private val service = ForkService().apply {
        forkRepository = ForkRepository().apply {
            databaseConfig = testDb
            forkBranchRepository = ForkBranchRepository().apply { databaseConfig = testDb }
        }
        commandEmitter = emitter
        databaseConfig = testDb
        functionResolver = mockk(relaxed = true)
        creationEventEnqueuer = CreationEventEnqueuer().also {
            it.repository = creationEventRepository
            it.publisher = object : LifecycleEventPublisher {
                override val enabled = true
                override suspend fun publish(payload: String) = Unit
            }
        }
        creationEventOutbox = this@ForkServiceCreationEventsH2Test.creationEventOutbox
        clock = object : Clock { override fun now() = acceptedAt }
    }

    @BeforeEach
    fun setUp() = runBlocking {
        WorkflowCache.clear()
        sent.clear()
        creationEventRepository.deleteAll()
        Unit
    }

    private suspend fun forkStarted(): InstanceMessage<WorkflowEvent.ForkStarted> {
        val workflow = WorkflowCache.parseYamlAndPut(yaml)
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd()
        while (true) {
            when (val event = StepByStepOrchestrator.runByTask(workflow, command, workflow.info, com.lemline.core.lifecycleevents.LifecycleEventHook.NOOP)) {
                is WorkflowEvent.TaskScheduled -> command = event.resume()
                is WorkflowEvent.ForkStarted -> return InstanceMessage(workflow.info, event)
                else -> error("Unexpected $event")
            }
        }
    }

    @Test
    fun `branches are created with the fork and their wait closes when a worker starts them`() = runBlocking {
        val fork = forkStarted()
        service.handleForkStarted(fork)

        // One command per branch, each carrying the fork's acceptance time.
        val branches = sent.map { it.workflowState as WorkflowCommand.ResumeFromTask }
        branches.map { it.nodePosition.toString() }.toSet() shouldBe setOf("/do/0/split/fork/branches/0/left", "/do/0/split/fork/branches/1/right")
        branches.forEach { it.createdAt shouldBe acceptedAt }

        // One durable task.created per branch, stamped with the same instant, published independently.
        val created = creationEventRepository.findByWorkflowId(fork.workflowState.workflowId)
        created.size shouldBe 2
        created.forEach {
            it.eventType shouldBe LifecycleEventType.TASK_CREATED.type
            it.eventTime shouldBe Instant.fromEpochSeconds(acceptedAt.epochSeconds, acceptedAt.nanosecondsOfSecond / 1_000 * 1_000)
        }
        val kicked = slot<Collection<IDV7>>()
        verify(exactly = 1) { creationEventOutbox.processNow(capture(kicked)) }
        kicked.captured.toSet() shouldBe created.map { it.id }.toSet()

        // A worker picks a branch up later: task.started, no second task.created.
        val workerEvents = CopyOnWriteArrayList<CloudEvent>()
        val hook = CloudEventLifecycleHook(object : LifecycleEventEmitter {
            override suspend fun emit(cloudEvent: CloudEvent) { workerEvents.add(cloudEvent) }
        })
        val left = branches.single { it.nodePosition.toString().endsWith("/left") }
        StepByStepOrchestrator.runByTask(WorkflowCache.parseYamlAndPut(yaml), left, fork.workflowInfo, hook)
        val started = workerEvents.single { it.type == LifecycleEventType.TASK_STARTED.type }
        (started.time!!.toInstant() >= acceptedAt.toJavaInstantCompat().plusSeconds(5)) shouldBe true
        workerEvents.filter { it.type == LifecycleEventType.TASK_CREATED.type }.shouldBeEmpty()
    }

    @Test
    fun `a redelivered fork creates no second branch invocation`() = runBlocking {
        val fork = forkStarted()
        service.handleForkStarted(fork)
        service.handleForkStarted(fork)
        sent.size shouldBe 2
        creationEventRepository.findByWorkflowId(fork.workflowState.workflowId).size shouldBe 2
    }

    private fun Instant.toJavaInstantCompat(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    companion object {
        private val testDb = H2TestDatabaseConfig().also { it.migrate() }

        @AfterAll
        @JvmStatic
        fun teardown() = testDb.close()
    }
}
