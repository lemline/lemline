// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.info
import com.lemline.core.lifecycleevents.LifecycleEventHook
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
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Child workflows are accepted with their parent record: same transaction, same creation time. */
@DisplayName("ParentService child creation events [H2]")
class ParentServiceCreationEventsH2Test {

    private val childYaml = """
        document: { dsl: '1.0.0', namespace: kids, name: child, version: '1.0.0' }
        do:
          - work: { set: { done: true } }
    """.trimIndent()

    private fun parentYaml(await: Boolean) = """
        document: { dsl: '1.0.0', namespace: kids, name: parent-$await, version: '1.0.0' }
        do:
          - spawn:
              run:
                await: $await
                workflow: { namespace: kids, name: child, version: '1.0.0' }
    """.trimIndent()

    private val sent = CopyOnWriteArrayList<InstanceMessage<out WorkflowCommand>>()
    private val creationEventRepository = CreationEventRepository().apply { databaseConfig = testDb }
    private val acceptedAt = Instant.parse("2026-06-07T08:09:10.111222Z")
    private val definitions = mockk<Definitions>()

    private val service = ParentService().apply {
        parentRepository = ParentRepository().apply { databaseConfig = testDb }
        scheduleRepository = ScheduleRepository().apply { databaseConfig = testDb }
        starter = Starter().apply { definitions = this@ParentServiceCreationEventsH2Test.definitions }
        commandEmitter = object : CommandEmitter {
            override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
                sent.add(message)
            }
        }
        databaseConfig = testDb
        creationEventEnqueuer = CreationEventEnqueuer().also {
            it.repository = creationEventRepository
            it.publisher = object : LifecycleEventPublisher {
                override val enabled = true
                override suspend fun publish(payload: String) = Unit
            }
        }
        creationEventOutbox = mockk<CreationEventOutbox>(relaxed = true)
        clock = object : Clock { override fun now() = acceptedAt }
    }

    @BeforeEach
    fun setUp() = runBlocking {
        WorkflowCache.clear()
        sent.clear()
        creationEventRepository.deleteAll()
        coEvery { definitions.get(WorkflowNamespace("kids"), WorkflowName("child"), any()) } returns WorkflowCache.parseYaml(childYaml)
        Unit
    }

    private suspend fun runWorkflowStarted(await: Boolean): InstanceMessage<WorkflowEvent.RunWorkflowStarted> {
        val workflow = WorkflowCache.parseYamlAndPut(parentYaml(await))
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd()
        while (true) {
            when (val event = StepByStepOrchestrator.runByTask(workflow, command, workflow.info, LifecycleEventHook.NOOP)) {
                is WorkflowEvent.TaskScheduled -> command = event.resume()
                is WorkflowEvent.RunWorkflowStarted -> return InstanceMessage(workflow.info, event)
                else -> error("Unexpected $event")
            }
        }
    }

    @Test
    fun `synchronous child creation events commit with the parent`() = assertChildCreation(await = true)

    @Test
    fun `asynchronous child creation events commit with the parent`() = assertChildCreation(await = false)

    private fun assertChildCreation(await: Boolean) = runBlocking {
        val started = runWorkflowStarted(await)
        service.handleRunWorkflowStarted(started)

        val child = sent.single().workflowState as WorkflowCommand.ResumeFromTask
        child.createdAt shouldBe acceptedAt
        child.nodeStack.rootState.hasWaitingParent shouldBe await
        val created = creationEventRepository.findByWorkflowId(WorkflowId(child.workflowId.value))
        created.map { it.eventType }.toSet() shouldBe setOf(LifecycleEventType.WORKFLOW_CREATED.type, LifecycleEventType.TASK_CREATED.type)
        created.forEach { it.eventTime shouldBe acceptedAt }

        // Redelivery of RunWorkflowStarted: the parent exists, nothing is created twice.
        service.copyWithLaterClock().handleRunWorkflowStarted(started)
        sent.size shouldBe 1
        creationEventRepository.findByWorkflowId(WorkflowId(child.workflowId.value)) shouldBe created
    }

    private fun ParentService.copyWithLaterClock() = apply { clock = object : Clock { override fun now() = acceptedAt + 30.seconds } }

    companion object {
        private val testDb = H2TestDatabaseConfig().also { it.migrate() }

        @AfterAll
        @JvmStatic
        fun teardown() = testDb.close()
    }
}
