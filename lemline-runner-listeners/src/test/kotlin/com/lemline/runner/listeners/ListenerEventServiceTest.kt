// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

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
import com.lemline.core.workflows.CachedListenTask
import com.lemline.core.workflows.CachedUntilCondition
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.net.URI
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for ListenerEventService.
 * These tests verify CloudEvent processing and routing to appropriate handlers.
 */
@DisplayName("ListenerEventService")
class ListenerEventServiceTest {

    private val mockedListenerRepository = mockk<ListenerRepository>()
    private val mockedListenerEventRepository = mockk<ListenerEventRepository>()
    private val mockedDefinitionListenService = mockk<DefinitionListenService>()
    private val mockedDatabaseConfig = mockk<DatabaseConfig>()

    private val service = ListenerEventService().apply {
        this.listenerRepository = mockedListenerRepository
        this.listenerEventRepository = mockedListenerEventRepository
        this.definitionListenService = mockedDefinitionListenService
        this.databaseConfig = mockedDatabaseConfig
    }

    @BeforeEach
    fun setup() {
        clearAllMocks()
        coEvery { mockedDatabaseConfig.withTransaction<Any?>(any(), any()) } coAnswers {
            val block = secondArg<suspend (java.sql.Connection?) -> Any?>()
            block(null)
        }
    }

    // ============================================================================
    // Test Helpers
    // ============================================================================

    private fun buildCloudEvent(
        type: String = "com.example.Event",
        source: String = "https://test.example.com",
        id: String = "test-event-${IDV7.random()}",
        data: String? = null
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(id)
            .withType(type)
            .withSource(URI.create(source))

        data?.let { builder.withData("application/json", it.toByteArray()) }

        return builder.build()
    }

    // Accumulated events must be valid CloudEvent JSON (not just data payloads)
    private fun buildSerializedCloudEvent(
        data: String = """{"count":5}""",
        id: String = "test-event-${IDV7.random()}"
    ): String {
        val event = buildCloudEvent(data = data, id = id)
        return CloudEventService.serialize(event)
    }

    private fun createListenerModel(
        strategy: ListenerStrategy = ListenerStrategy.ONE,
        untilExpression: String? = null,
        hasForeach: Boolean = false
    ): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())
        val workflowInfo = WorkflowInfo(
            WorkflowNamespace("test-namespace"),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0")
        )
        val nodePosition = NodePosition("/do/0/listenTask")

        val nodeStack = NodeStack.fromFrames(
            listOf(
                StackFrame(
                    NodePosition.root, RootState(
                        startedAt = now,
                        workflowId = workflowId,
                        workflowInput = JsonNull
                    )
                ),
                StackFrame(NodePosition("/do"), DoState(startedAt = now)),
                StackFrame(NodePosition("/do/0"), DoState(startedAt = now)),
                StackFrame(nodePosition, TaskState(startedAt = now))
            )
        )

        val config = ListenConfig(
            strategy = when (strategy) {
                ListenerStrategy.ONE -> ListenStrategy.ONE
                ListenerStrategy.ANY, ListenerStrategy.ANY_UNTIL_EXPR, ListenerStrategy.ANY_UNTIL_EVENT -> ListenStrategy.ANY
                ListenerStrategy.ALL -> ListenStrategy.ALL
            },
            filters = listOf(EventFilter(type = "com.example.Event")),
            readAs = ListenAndReadAs.DATA,
            timeoutAt = null
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = config
        )

        return ListenerModel(
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
            id = IDV7.random(),
            listenerStrategy = strategy,
            timeoutAt = null,
        ).apply {
            this.untilExpression = untilExpression
            this.hasForeach = hasForeach
            outboxScheduledFor = now
        }
    }

    private fun createMatchingListenTask(
        strategy: ListenerStrategy = ListenerStrategy.ONE,
        filterIndex: Int = 0,
        correlationValuesJson: String? = null
    ): MatchingListenTask {
        val workflowInfo = WorkflowInfo(
            WorkflowNamespace("test-namespace"),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0")
        )

        val cachedListenTask = mockk<CachedListenTask>()
        every { cachedListenTask.workflowInfo } returns workflowInfo
        every { cachedListenTask.nodePosition } returns NodePosition("/do/0/listenTask")
        every { cachedListenTask.strategy } returns when (strategy) {
            ListenerStrategy.ONE -> ListenStrategy.ONE
            ListenerStrategy.ANY, ListenerStrategy.ANY_UNTIL_EXPR, ListenerStrategy.ANY_UNTIL_EVENT -> ListenStrategy.ANY
            ListenerStrategy.ALL -> ListenStrategy.ALL
        }
        every { cachedListenTask.until } returns when (strategy) {
            ListenerStrategy.ANY_UNTIL_EXPR -> CachedUntilCondition.Expression("length > 0")
            ListenerStrategy.ANY_UNTIL_EVENT -> mockk<CachedUntilCondition.Event>()
            else -> null
        }
        every { cachedListenTask.readAs } returns ListenAndReadAs.DATA
        every { cachedListenTask.hasForeach } returns false
        every { cachedListenTask.isFromFunction } returns false

        return MatchingListenTask(
            listenTask = cachedListenTask,
            correlationValuesJson = correlationValuesJson,
            filterIndex = filterIndex
        )
    }

    private fun createMatchingUntilEvent(): MatchingListenTaskUntilEvent {
        val workflowInfo = WorkflowInfo(
            WorkflowNamespace("test-namespace"),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0")
        )

        val cachedListenTask = mockk<CachedListenTask>()
        every { cachedListenTask.workflowInfo } returns workflowInfo
        every { cachedListenTask.nodePosition } returns NodePosition("/do/0/listenTask")
        every { cachedListenTask.readAs } returns ListenAndReadAs.DATA
        every { cachedListenTask.hasForeach } returns false
        every { cachedListenTask.isFromFunction } returns false

        return MatchingListenTaskUntilEvent(listenTask = cachedListenTask)
    }

    // ============================================================================
    // handleCloudEvent - No Matching Tasks
    // ============================================================================

    @Nested
    @DisplayName("When no tasks match the event")
    inner class NoMatchingTasksTests {

        @Test
        fun `should return 0 when no listen tasks and no until events match`() = runTest {
            // Given
            val event = buildCloudEvent()
            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns emptyList()
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 0
            coVerify(exactly = 0) { mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
        }

        @Test
        fun `should not access event data if no tasks match`() = runTest {
            // Given
            val event = buildCloudEvent(data = """{"shouldNotBeParsed":true}""")
            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns emptyList()
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 0
        }
    }

    // ============================================================================
    // handleCloudEvent - ONE and ANY Strategy
    // ============================================================================

    @Nested
    @DisplayName("When ONE/ANY strategy tasks match")
    inner class OneAnyStrategyTests {

        @Test
        fun `should insert event for ONE strategy listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
            coVerify {
                mockedListenerEventRepository.batchInsertForOneAny(
                    keys = match { it.size == 1 && it[0].listenerStrategy == ListenerStrategy.ONE },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should insert event for ANY strategy listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
            coVerify {
                mockedListenerEventRepository.batchInsertForOneAny(
                    keys = match { it.size == 1 && it[0].listenerStrategy == ListenerStrategy.ANY },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should handle multiple ONE and ANY listeners`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTasks = listOf(
                createMatchingListenTask(strategy = ListenerStrategy.ONE),
                createMatchingListenTask(strategy = ListenerStrategy.ANY)
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns matchingTasks
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 2

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 2
            coVerify {
                mockedListenerEventRepository.batchInsertForOneAny(
                    keys = match { it.size == 2 },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should serialize CloudEvent correctly for storage`() = runTest {
            // Given
            val event = buildCloudEvent(
                type = "com.example.TestEvent",
                data = """{"key":"value"}"""
            )
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()

            val capturedEventJson = slot<String>()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), capture(capturedEventJson), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            Json.parseToJsonElement(capturedEventJson.captured)
            capturedEventJson.captured.contains("com.example.TestEvent") shouldBe true
        }

        @Test
        fun `should deduplicate query keys for same listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            // Two matching tasks with same workflow info (would generate duplicate keys)
            val matchingTasks = listOf(
                createMatchingListenTask(strategy = ListenerStrategy.ONE, filterIndex = 0),
                createMatchingListenTask(strategy = ListenerStrategy.ONE, filterIndex = 0) // Same filter index
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns matchingTasks
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            coVerify {
                mockedListenerEventRepository.batchInsertForOneAny(
                    keys = match { it.distinct().size == it.size }, // Keys should be deduplicated
                    eventId = any(),
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should not call batchInsertForAccumulating for ONE or ANY only`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            coVerify(exactly = 0) {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
        }
    }

    // ============================================================================
    // handleCloudEvent - ALL Strategy
    // ============================================================================

    @Nested
    @DisplayName("When ALL strategy tasks match")
    inner class AllStrategyTests {

        @Test
        fun `should insert event for ALL strategy listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 0)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
            coVerify {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    keys = match { it.size == 1 && it[0].listenerStrategy == ListenerStrategy.ALL },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should handle ALL strategy with multiple filter indices`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTasks = listOf(
                createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 0),
                createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 1),
                createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 2)
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns matchingTasks
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 3

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 3
            coVerify {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    keys = match { it.size == 3 },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should not call batchInsertForOneAny for ALL only`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ALL)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            coVerify(exactly = 0) { mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any()) }
        }
    }

    // ============================================================================
    // handleCloudEvent - ANY_UNTIL_EXPR Strategy
    // ============================================================================

    @Nested
    @DisplayName("When ANY_UNTIL_EXPR strategy tasks match")
    inner class AnyUntilExprStrategyTests {

        @Test
        fun `should insert event for ANY_UNTIL_EXPR strategy listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns emptyList()

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
            coVerify {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    keys = match { it.size == 1 && it[0].listenerStrategy == ListenerStrategy.ANY_UNTIL_EXPR },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should evaluate until expression after inserting event`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns emptyList()

            // When
            service.handleCloudEvent(event)

            // Then
            coVerify {
                mockedListenerRepository.findListenersByKeysWithEvents(
                    keys = match { it.any { key -> key.listenerStrategy == ListenerStrategy.ANY_UNTIL_EXPR } },
                    connection = null
                )
            }
        }

        @Test
        fun `should mark listener completed when until expression evaluates to true`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)
            val listener = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length > 0" // Expression that returns true
            )
            val accumulatedEvents = listOf(buildSerializedCloudEvent(data = """{"count":5}"""))

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns listOf(listener to accumulatedEvents)
            coEvery {
                mockedListenerRepository.batchMarkListenersCompleted(any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 2 // 1 for insert + 1 for marking completed
            coVerify {
                mockedListenerRepository.batchMarkListenersCompleted(
                    ids = listOf(listener.id),
                    connection = null
                )
            }
        }

        @Test
        fun `should not mark listener completed when until expression evaluates to false`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)
            val listener = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length > 100" // Expression that returns false
            )
            val accumulatedEvents = listOf(buildSerializedCloudEvent(data = """{"count":5}"""))

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns listOf(listener to accumulatedEvents)

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1 // Only insert, no completion
            coVerify(exactly = 0) {
                mockedListenerRepository.batchMarkListenersCompleted(any(), any())
            }
        }

        @Test
        fun `should skip until expression evaluation for listener without untilExpression`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)
            val listener = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = null // No expression set
            )
            val accumulatedEvents = listOf("""{"data":{"count":5}}""")

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns listOf(listener to accumulatedEvents)

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1 // Only insert
            coVerify(exactly = 0) {
                mockedListenerRepository.batchMarkListenersCompleted(any(), any())
            }
        }
    }

    // ============================================================================
    // handleCloudEvent - ANY_UNTIL_EVENT Strategy
    // ============================================================================

    @Nested
    @DisplayName("When ANY_UNTIL_EVENT strategy tasks match")
    inner class AnyUntilEventStrategyTests {

        @Test
        fun `should insert event for ANY_UNTIL_EVENT strategy listener`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EVENT)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
            coVerify {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    keys = match { it.size == 1 && it[0].listenerStrategy == ListenerStrategy.ANY_UNTIL_EVENT },
                    eventId = event.id,
                    eventJson = any(),
                    connection = null
                )
            }
        }

        @Test
        fun `should process termination event and mark listeners completed`() = runTest {
            // Given
            val terminationEvent = buildCloudEvent(type = "com.example.Terminate")
            val matchingUntilEvent = createMatchingUntilEvent()

            every {
                mockedDefinitionListenService.findMatchingListenTasks(terminationEvent, any())
            } returns emptyList()
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(terminationEvent)
            } returns listOf(matchingUntilEvent)
            coEvery {
                mockedListenerRepository.markListenerCompletedByUntilEvent(any(), any())
            } returns 3

            // When
            val result = service.handleCloudEvent(terminationEvent)

            // Then
            result shouldBe 3
            coVerify {
                mockedListenerRepository.markListenerCompletedByUntilEvent(
                    keys = match { it.isNotEmpty() },
                    connection = null
                )
            }
        }

        @Test
        fun `should handle event that matches both listen tasks and until events`() = runTest {
            // Given: An event that matches both regular filters AND termination filters
            val event = buildCloudEvent()
            val matchingListenTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EVENT)
            val matchingUntilEvent = createMatchingUntilEvent()

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingListenTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns listOf(matchingUntilEvent)
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.markListenerCompletedByUntilEvent(any(), any())
            } returns 2

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 3 // 1 insert + 2 terminations
        }

        @Test
        fun `should return 0 for termination when no matching listeners`() = runTest {
            // Given
            val terminationEvent = buildCloudEvent(type = "com.example.Terminate")
            val matchingUntilEvent = createMatchingUntilEvent()

            every {
                mockedDefinitionListenService.findMatchingListenTasks(terminationEvent, any())
            } returns emptyList()
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(terminationEvent)
            } returns listOf(matchingUntilEvent)
            coEvery {
                mockedListenerRepository.markListenerCompletedByUntilEvent(any(), any())
            } returns 0

            // When
            val result = service.handleCloudEvent(terminationEvent)

            // Then
            result shouldBe 0
        }
    }

    // ============================================================================
    // handleCloudEvent - Mixed Strategies
    // ============================================================================

    @Nested
    @DisplayName("When multiple strategy types match simultaneously")
    inner class MixedStrategiesTests {

        @Test
        fun `should handle both ONE and ALL strategies in same event`() = runTest {
            // Given
            val event = buildCloudEvent()
            val oneTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)
            val allTask = createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 0)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(oneTask, allTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 2
            coVerify(exactly = 1) { mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
        }

        @Test
        fun `should handle ALL and ANY_UNTIL_EXPR in same event`() = runTest {
            // Given
            val event = buildCloudEvent()
            val allTask = createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 0)
            val exprTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(allTask, exprTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 2
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns emptyList() // No expression evaluation needed

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 2
            coVerify(exactly = 1) {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
            coVerify(exactly = 0) { mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any()) }
        }

        @Test
        fun `should handle all strategy types simultaneously`() = runTest {
            // Given
            val event = buildCloudEvent()
            val tasks = listOf(
                createMatchingListenTask(strategy = ListenerStrategy.ONE),
                createMatchingListenTask(strategy = ListenerStrategy.ANY),
                createMatchingListenTask(strategy = ListenerStrategy.ALL, filterIndex = 0),
                createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR),
                createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EVENT)
            )
            val untilEvent = createMatchingUntilEvent()

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns tasks
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns listOf(untilEvent)
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 2 // ONE + ANY
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 3 // ALL + ANY_UNTIL_EXPR + ANY_UNTIL_EVENT
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns emptyList()
            coEvery {
                mockedListenerRepository.markListenerCompletedByUntilEvent(any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 6 // 2 + 3 + 1 (termination)
        }
    }

    // ============================================================================
    // handleCloudEvent - Correlation Values
    // ============================================================================

    @Nested
    @DisplayName("When handling correlation values")
    inner class CorrelationTests {

        @Test
        fun `should pass correlation values to query key`() = runTest {
            // Given
            val event = buildCloudEvent(data = """{"orderId":"ORD-123"}""")
            val matchingTask = createMatchingListenTask(
                strategy = ListenerStrategy.ONE,
                correlationValuesJson = """{"orderId":"ORD-123"}"""
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()

            val capturedKeys = slot<List<ListenerQueryKey>>()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(capture(capturedKeys), any(), any(), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            capturedKeys.captured.size shouldBe 1
            capturedKeys.captured[0].correlationValuesJson shouldBe """{"orderId":"ORD-123"}"""
        }

        @Test
        fun `should handle null correlation values`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(
                strategy = ListenerStrategy.ONE,
                correlationValuesJson = null
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()

            val capturedKeys = slot<List<ListenerQueryKey>>()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(capture(capturedKeys), any(), any(), any())
            } returns 1

            // When
            service.handleCloudEvent(event)

            // Then
            capturedKeys.captured.size shouldBe 1
            capturedKeys.captured[0].correlationValuesJson shouldBe null
        }
    }

    // ============================================================================
    // evaluateUntilExpression
    // ============================================================================

    @Nested
    @DisplayName("evaluateUntilExpression")
    inner class EvaluateUntilExpressionTests {

        @Test
        fun `should return true for valid expression that evaluates to true`() {
            // Given
            val events = JsonArray(
                listOf(
                    JsonPrimitive(1),
                    JsonPrimitive(2),
                    JsonPrimitive(3)
                )
            )

            // When
            val result = service.evaluateUntilExpression("length > 2", events)

            // Then
            result shouldBe true
        }

        @Test
        fun `should return false for valid expression that evaluates to false`() {
            // Given
            val events = JsonArray(
                listOf(
                    JsonPrimitive(1),
                    JsonPrimitive(2)
                )
            )

            // When
            val result = service.evaluateUntilExpression("length > 5", events)

            // Then
            result shouldBe false
        }

        @Test
        fun `should return false for empty events array with length check`() {
            // Given
            val events = JsonArray(emptyList())

            // When
            val result = service.evaluateUntilExpression("length > 0", events)

            // Then
            result shouldBe false
        }

        @Test
        fun `should return true for empty events array with length equals zero`() {
            // Given
            val events = JsonArray(emptyList())

            // When
            val result = service.evaluateUntilExpression("length == 0", events)

            // Then
            result shouldBe true
        }

        @Test
        fun `should return false for invalid expression`() {
            // Given
            val events = JsonArray(listOf(JsonPrimitive(1)))

            // When
            val result = service.evaluateUntilExpression("invalid$#@expression", events)

            // Then
            result shouldBe false
        }

        @Test
        fun `should return false for expression that returns non-boolean`() {
            // Given
            val events = JsonArray(
                listOf(
                    JsonPrimitive(1),
                    JsonPrimitive(2)
                )
            )

            // When - Expression returns a number, not boolean
            val result = service.evaluateUntilExpression("length", events)

            // Then
            result shouldBe false
        }

        @Test
        fun `should evaluate complex JQ expressions`() {
            // Given
            val events = JsonArray(
                listOf(
                    Json.parseToJsonElement("""{"value":10}"""),
                    Json.parseToJsonElement("""{"value":20}"""),
                    Json.parseToJsonElement("""{"value":30}""")
                )
            )

            // When - Sum all values and check if > 50
            val result = service.evaluateUntilExpression("[.[].value] | add > 50", events)

            // Then
            result shouldBe true
        }

        @Test
        fun `should handle null in events array`() {
            // Given
            val events = JsonArray(
                listOf(
                    JsonNull,
                    JsonPrimitive(1)
                )
            )

            // When
            val result = service.evaluateUntilExpression("length > 1", events)

            // Then
            result shouldBe true
        }

        @Test
        fun `should evaluate expression with map and filter`() {
            // Given
            val events = JsonArray(
                listOf(
                    Json.parseToJsonElement("""{"status":"completed"}"""),
                    Json.parseToJsonElement("""{"status":"pending"}"""),
                    Json.parseToJsonElement("""{"status":"completed"}""")
                )
            )

            // When - Check if at least 2 events have status completed
            val result =
                service.evaluateUntilExpression("[.[] | select(.status == \"completed\")] | length >= 2", events)

            // Then
            result shouldBe true
        }

        @Test
        fun `should handle deeply nested event data`() {
            // Given
            val events = JsonArray(
                listOf(
                    Json.parseToJsonElement("""{"data":{"nested":{"value":100}}}""")
                )
            )

            // When
            val result = service.evaluateUntilExpression(".[0].data.nested.value > 50", events)

            // Then
            result shouldBe true
        }
    }

    // ============================================================================
    // Error Handling
    // ============================================================================

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandlingTests {

        @Test
        fun `should handle expression evaluation errors gracefully`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)
            val listener = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "some.deeply.nested.path.that.fails" // Will fail on empty events
            )
            val accumulatedEvents = listOf<String>() // Empty list to cause potential issues

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns listOf(listener to accumulatedEvents)

            // When - Should not throw exception
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1 // Only the insert, no completion due to failed expression
            coVerify(exactly = 0) { mockedListenerRepository.batchMarkListenersCompleted(any(), any()) }
        }
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        @Test
        fun `should handle event with no data field`() = runTest {
            // Given
            val event = buildCloudEvent(data = null)
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
        }

        @Test
        fun `should handle very long event data`() = runTest {
            // Given
            val largeData = """{"data":"${"x".repeat(10000)}"}"""
            val event = buildCloudEvent(data = largeData)
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 1

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 1
        }

        @Test
        fun `should return 0 when repository inserts return 0`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ONE)

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForOneAny(any(), any(), any(), any())
            } returns 0 // No listeners matched the query

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 0
        }

        @Test
        fun `should handle multiple listeners completing their until expressions`() = runTest {
            // Given
            val event = buildCloudEvent()
            val matchingTask = createMatchingListenTask(strategy = ListenerStrategy.ANY_UNTIL_EXPR)
            val listener1 = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length > 0"
            )
            val listener2 = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length > 0"
            )
            val listener3 = createListenerModel(
                strategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length > 100" // This one won't complete
            )

            every {
                mockedDefinitionListenService.findMatchingListenTasks(event, any())
            } returns listOf(matchingTask)
            every {
                mockedDefinitionListenService.findMatchingUntilEvents(event)
            } returns emptyList()
            coEvery {
                mockedListenerEventRepository.batchInsertForAllAnyUntil(any(), any(), any(), any())
            } returns 1
            coEvery {
                mockedListenerRepository.findListenersByKeysWithEvents(any(), any())
            } returns listOf(
                listener1 to listOf(buildSerializedCloudEvent(data = """{"data":"event1"}""", id = "event1")),
                listener2 to listOf(buildSerializedCloudEvent(data = """{"data":"event2"}""", id = "event2")),
                listener3 to listOf(buildSerializedCloudEvent(data = """{"data":"event3"}""", id = "event3"))
            )
            coEvery {
                mockedListenerRepository.batchMarkListenersCompleted(any(), any())
            } returns 2 // Only 2 should complete

            // When
            val result = service.handleCloudEvent(event)

            // Then
            result shouldBe 3 // 1 insert + 2 completions
            coVerify {
                mockedListenerRepository.batchMarkListenersCompleted(
                    ids = match { it.size == 2 && it.contains(listener1.id) && it.contains(listener2.id) },
                    connection = null
                )
            }
        }
    }
}
