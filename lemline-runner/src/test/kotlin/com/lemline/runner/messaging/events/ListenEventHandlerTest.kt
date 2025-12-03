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
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionListenRepository
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerRepository
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
 * Note: These tests require a listen definition to exist in the database before
 * sending a ListenStarted event, as the handler looks up the definition to get
 * the listenDefinitionId.
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

    @Inject
    lateinit var definitionListenRepository: DefinitionListenRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/listenTask")

    @BeforeEach
    fun setup() = runTest {
        listenerRepository.deleteAll()
        definitionListenRepository.deleteAll()
    }

    private suspend fun createDefinition() {
        val definition = DefinitionModel(
            namespace = testNamespace,
            name = testName,
            version = testVersion,
            definition = """
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
        )
        definitionRepository.insert(definition)
    }

    private suspend fun createListenDefinition(
        strategy: ListenStrategy = ListenStrategy.ONE,
        readAs: ListenAndReadAs = ListenAndReadAs.DATA,
        nodePosition: NodePosition = testNodePosition
    ): DefinitionListenModel {
        createDefinition()
        val listenDef = DefinitionListenModel(
            id = IDV7.random(),
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion,
            nodePosition = nodePosition,
            strategy = strategy,
            readAs = readAs
        )
        definitionListenRepository.insert(listenDef)
        return listenDef
    }

    @Test
    fun `ListenStarted creates listener row with listenDefinitionId`() = runTest {
        // Given: A listen definition exists
        val listenDef = createListenDefinition(strategy = ListenStrategy.ONE)

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.OrderCreated")),
            nodePosition = testNodePosition
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByListenDefinitionId(listenDef.id)

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.workflowId shouldBe instance.workflowId
        listener.workflowPosition shouldBe instance.workflowState.nodePosition
        listener.listenDefinitionId shouldBe listenDef.id
    }

    @Test
    fun `ListenStarted with timeout sets timeoutAt`() = runTest {
        // Given
        val listenDef = createListenDefinition()
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
        val listeners = listenerRepository.findByListenDefinitionId(listenDef.id)

        listeners.size shouldBe 1
        listeners.first().timeoutAt shouldNotBe null
        // Allow some tolerance for timestamp comparison
        listeners.first().timeoutAt!!.epochSeconds shouldBe timeoutAt.epochSeconds
    }

    @Test
    fun `ListenStarted with ALL strategy creates listener referencing definition`() = runTest {
        // Given: A listen definition with ALL strategy exists
        val listenDef = createListenDefinition(strategy = ListenStrategy.ALL)

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ALL,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2"),
                EventFilter(type = "com.example.Event3")
            ),
            nodePosition = testNodePosition
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByListenDefinitionId(listenDef.id)

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.listenDefinitionId shouldBe listenDef.id

        // Verify we can look up the strategy from the definition
        val retrievedDef = definitionListenRepository.findById(listener.listenDefinitionId)
        retrievedDef shouldNotBe null
        retrievedDef!!.strategy shouldBe ListenStrategy.ALL
    }

    @Test
    fun `ListenStarted idempotent - second insert is ignored`() = runTest {
        // Given
        val listenDef = createListenDefinition()

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
        val listeners = listenerRepository.findByListenDefinitionId(listenDef.id)
        listeners.size shouldBe 1
    }

    @Test
    fun `ListenStarted with ENVELOPE readAs`() = runTest {
        // Given: A definition with ENVELOPE readAs
        val listenDef = createListenDefinition(
            strategy = ListenStrategy.ANY,
            readAs = ListenAndReadAs.ENVELOPE
        )

        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ANY,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2")
            ),
            readAs = ListenAndReadAs.ENVELOPE,
            nodePosition = testNodePosition
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByListenDefinitionId(listenDef.id)
        listeners.size shouldBe 1

        // Verify the definition has correct readAs
        val retrievedDef = definitionListenRepository.findById(listeners.first().listenDefinitionId)
        retrievedDef!!.readAs shouldBe ListenAndReadAs.ENVELOPE
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
        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                nodePosition to TaskState(startedAt = now)
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
                workflowNamespace = testNamespace,
                workflowName = testName,
                workflowVersion = testVersion
            ),
            workflowState = listenStarted
        )
    }
}
