// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.DoState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for the ListenStarted event handling in WorkflowEventHandler.
 *
 * The handler creates a listener with workflow identity (namespace, name, version)
 * which is used to locate the listen task configuration from the cached workflow definition.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenEventHandlerTest {

    @Inject
    lateinit var workflowEventHandler: WorkflowEventHandler

    @Inject
    lateinit var listenerRepository: ListenerRepository

    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/0/listenTask")

    @BeforeEach
    fun setup() = runTest {
        listenerRepository.deleteAll()
        WorkflowCache.clear()
    }

    private fun cacheWorkflowDefinition() {
        // Cache the workflow definition so listen task config can be retrieved
        val definition = """
            document:
              dsl: '1.0.0'
              namespace: $testNamespace
              name: $testName
              version: '$testVersion'
            do:
              - listenTask:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.OrderCreated
        """.trimIndent()
        WorkflowCache.parseYamlAndPut(definition)
    }

    @Test
    fun `ListenStarted creates listener row with workflow identity`() = runTest {
        // Given: A workflow definition is cached
        cacheWorkflowDefinition()

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.OrderCreated")),
            nodePosition = testNodePosition
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.listAll().filter { it.workflowId == instance.workflowId }

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.workflowId shouldBe instance.workflowId
        listener.nodePosition shouldBe instance.workflowState.nodePosition
        listener.workflowNamespace shouldBe testNamespace
        listener.workflowName shouldBe testName
        listener.workflowVersion shouldBe testVersion
    }

    @Test
    fun `ListenStarted with timeout sets timeoutAt`() = runTest {
        // Given
        cacheWorkflowDefinition()
        val timeoutAt = Clock.System.now() + 10.minutes

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.Event")),
            timeoutAt = timeoutAt,
            nodePosition = testNodePosition
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.listAll().filter { it.workflowId == instance.workflowId }

        listeners.size shouldBe 1
        listeners.first().timeoutAt shouldNotBe null
        listeners.first().timeoutAt!!.epochSeconds shouldBe timeoutAt.epochSeconds
    }

    @Test
    fun `ListenStarted idempotent - second insert is ignored`() = runTest {
        // Given
        cacheWorkflowDefinition()

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.Event")),
            nodePosition = testNodePosition
        )

        // When - handle twice
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then - only one row
        val listeners = listenerRepository.listAll().filter { it.workflowId == instance.workflowId }
        listeners.size shouldBe 1
    }

    // Helper function to create ListenStarted instance messages
    private fun createListenStartedInstance(
        strategy: ListenStrategy,
        filters: List<EventFilter>,
        timeoutAt: kotlin.time.Instant? = null,
        readAs: ListenAndReadAs = ListenAndReadAs.DATA,
        nodePosition: NodePosition = testNodePosition
    ): InstanceMessage<WorkflowEvent.ListenStarted> {
        val workflowId = WorkflowId(IDV7.random())
        val now = Clock.System.now()

        // Create a proper NodeStack with the listen task position
        // The stack needs: root → do block → listen task
        val nodeStack = NodeStack.fromFrames(
            listOf(
                StackFrame(
                    NodePosition.root,
                    RootState(
                        startedAt = now,
                        workflowId = workflowId,
                        workflowInput = JsonNull
                    )
                ),
                StackFrame(NodePosition("/do"), DoState(startedAt = now)),
                StackFrame(nodePosition, TaskState(startedAt = now))
            )
        )

        val config = ListenConfig(
            strategy = strategy,
            filters = filters,
            readAs = readAs,
            timeoutAt = timeoutAt
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = config
        )

        return InstanceMessage(
            workflowInfo = WorkflowInfo(
                namespace = testNamespace,
                name = testName,
                version = testVersion
            ),
            workflowState = listenStarted
        )
    }
}
