// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
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

    @BeforeEach
    fun setup() = runTest {
        listenerRepository.deleteAll()
    }

    @Test
    fun `ListenStarted creates listener row`() = runTest {
        // Given
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(
                EventFilter(type = "com.example.OrderCreated")
            )
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.workflowId shouldBe instance.workflowId
        listener.workflowPosition shouldBe instance.workflowState.nodePosition
        listener.strategy shouldBe ListenStrategy.ONE
        listener.readAs shouldBe ListenAndReadAs.DATA
    }

    @Test
    fun `ListenStarted with timeout sets timeoutAt`() = runTest {
        // Given
        val timeoutAt = Clock.System.now() + 10.minutes
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.Event")),
            timeoutAt = timeoutAt
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
        listeners.first().timeoutAt shouldNotBe null
        // Allow some tolerance for timestamp comparison
        listeners.first().timeoutAt!!.epochSeconds shouldBe timeoutAt.epochSeconds
    }

    @Test
    fun `ListenStarted with ALL strategy stores config correctly`() = runTest {
        // Given
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ALL,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2"),
                EventFilter(type = "com.example.Event3")
            )
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.strategy shouldBe ListenStrategy.ALL

        // Parse config and verify filters
        val config = listener.parseConfig()
        config.filters.size shouldBe 3
        config.filters[0].type shouldBe "com.example.Event1"
        config.filters[1].type shouldBe "com.example.Event2"
        config.filters[2].type shouldBe "com.example.Event3"
    }

    @Test
    fun `ListenStarted with correlation stores config correctly`() = runTest {
        // Given
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(
                EventFilter(
                    type = "com.example.OrderEvent",
                    correlations = mapOf(
                        "orderId" to CorrelationDef(
                            from = ".orderId",
                            expect = "\${ .orderId }"
                        )
                    )
                )
            )
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
        val config = listeners.first().parseConfig()
        config.filters.first().correlations shouldNotBe null
        config.filters.first().correlations!!["orderId"]?.from shouldBe ".orderId"
        config.filters.first().correlations!!["orderId"]?.expect shouldBe "\${ .orderId }"
    }

    @Test
    fun `ListenStarted idempotent - second insert is ignored`() = runTest {
        // Given
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(type = "com.example.Event"))
        )

        // When - handle twice
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then - only one row
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
    }

    @Test
    fun `ListenStarted with ANY strategy and readAs envelope`() = runTest {
        // Given
        val instance = createListenStartedInstance(
            strategy = ListenStrategy.ANY,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2")
            ),
            readAs = ListenAndReadAs.ENVELOPE
        )

        // When
        @Suppress("UNCHECKED_CAST")
        workflowEventHandler.handle(instance as InstanceMessage<WorkflowEvent>)

        // Then
        val listeners = listenerRepository.findByDefinition(
            namespace = instance.workflowInfo.workflowNamespace,
            name = instance.workflowInfo.workflowName,
            version = instance.workflowInfo.workflowVersion
        )

        listeners.size shouldBe 1
        val listener = listeners.first()
        listener.strategy shouldBe ListenStrategy.ANY
        listener.readAs shouldBe ListenAndReadAs.ENVELOPE
    }

    // Helper function to create ListenStarted instance messages
    private fun createListenStartedInstance(
        strategy: ListenStrategy,
        filters: List<EventFilter>,
        timeoutAt: kotlin.time.Instant? = null,
        readAs: ListenAndReadAs = ListenAndReadAs.DATA
    ): InstanceMessage<WorkflowEvent.ListenStarted> {
        val workflowId = WorkflowId(IDV7.random())
        val namespace = WorkflowNamespace("test-namespace")
        val name = WorkflowName("test-workflow")
        val version = WorkflowVersion("1.0.0")

        // Create a proper NodeStack with only RootState
        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = workflowId,
                    workflowInput = JsonNull
                )
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
                workflowNamespace = namespace,
                workflowName = name,
                workflowVersion = version
            ),
            workflowState = listenStarted
        )
    }
}
