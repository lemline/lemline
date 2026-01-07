// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

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
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionRepository
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for ListenerEventRepository tests.
 * Provides common test infrastructure for testing against different database types.
 */
abstract class ListenerEventRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getEventRepository(): ListenerEventRepository
    protected abstract fun getListenerRepository(): ListenerRepository
    protected abstract fun getDefinitionRepository(): DefinitionRepository

    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/0/listenTask")
    private val testNodePosition2 = NodePosition("/do/1/listenTask2")
    private val testNodePosition3 = NodePosition("/do/2/listenTask3")

    private var eventIdCounter = 0

    @BeforeEach
    fun setup() = runTest {
        getEventRepository().deleteAll()
        getListenerRepository().deleteAll()

        // Create parent definition record
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
                          all:
                            - with:
                                type: com.example.Event1
                            - with:
                                type: com.example.Event2
            """.trimIndent()
        )
        try {
            getDefinitionRepository().insert(definition)
        } catch (_: Exception) {
            // Definition already exists
        }
    }

    private fun createListener(
        hasForeach: Boolean = false,
        strategy: ListenStrategy = ListenStrategy.ALL,
        filtersCount: Int = 2
    ): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())
        val workflowInfo = WorkflowInfo(testNamespace, testName, testVersion)

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
                StackFrame(testNodePosition, TaskState(startedAt = now))
            )
        )

        val filters = (1..filtersCount).map { EventFilter(type = "com.example.Event$it") }

        val config = ListenConfig(
            strategy = strategy,
            filters = filters,
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
            listenerStrategy = ListenerStrategy.from(config),
            timeoutAt = null,
        ).also {
            it.outboxScheduledFor = now
            it.hasForeach = hasForeach
            // Set filtersCount for ALL strategy (required for completion check)
            if (strategy == ListenStrategy.ALL) {
                it.filtersCount = filtersCount
            }
        }
    }

    private fun createListenerWithDifferentPosition(
        hasForeach: Boolean = false,
        strategy: ListenStrategy = ListenStrategy.ALL
    ): ListenerModel {
        return createListenerAtPosition(testNodePosition2, hasForeach, strategy)
    }

    private fun createListenerWithThirdPosition(
        hasForeach: Boolean = false,
        strategy: ListenStrategy = ListenStrategy.ALL
    ): ListenerModel {
        return createListenerAtPosition(testNodePosition3, hasForeach, strategy)
    }

    private fun createListenerAtPosition(
        position: NodePosition,
        hasForeach: Boolean = false,
        strategy: ListenStrategy = ListenStrategy.ALL
    ): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())
        val workflowInfo = WorkflowInfo(testNamespace, testName, testVersion)

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
                StackFrame(position, TaskState(startedAt = now))
            )
        )

        val filters = listOf(
            EventFilter(type = "com.example.Event1"),
            EventFilter(type = "com.example.Event2")
        )

        val config = ListenConfig(
            strategy = strategy,
            filters = filters,
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
            listenerStrategy = ListenerStrategy.from(config),
            timeoutAt = null,
        ).also {
            it.outboxScheduledFor = now
            it.hasForeach = hasForeach
            if (strategy == ListenStrategy.ALL) {
                it.filtersCount = filters.size
            }
        }
    }

    private fun createEvent(
        listenerId: IDV7,
        filterIndex: Int = 0,
        eventData: String = """{"type":"com.example.Event"}""",
        eventId: String? = null,
        sortKey: Int? = null
    ): ListenerEventModel {
        val counter = eventIdCounter++
        return ListenerEventModel(
            listenerId = listenerId,
            eventId = eventId ?: "event-$counter",
            filterIndex = filterIndex,
            event = eventData,
            outboxScheduledFor = Clock.System.now()
        ).apply {
            // Use auto-incrementing sort_key if not explicitly provided
            this.sortKey = sortKey ?: counter
        }
    }

    /** Creates a query key from a listener with the correct strategy. */
    private fun createQueryKey(
        listener: ListenerModel,
        filterIndex: Int? = null,
        correlationValuesJson: String? = null
    ) = ListenerQueryKey(
        workflowInfo = listener.instanceMessage.workflowInfo,
        position = listener.instanceMessage.workflowState.nodePosition,
        correlationValuesJson = correlationValuesJson,
        filterIndex = filterIndex,
        listenerStrategy = listener.listenerStrategy
    )

    // ========== Basic CRUD tests ==========

    @Test
    fun `insert multiple events should work`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        val events = listOf(
            createEvent(listener.id, filterIndex = 0, eventData = """{"type":"Event1"}"""),
            createEvent(listener.id, filterIndex = 1, eventData = """{"type":"Event2"}""")
        )

        val inserted = getEventRepository().insert(events)

        inserted shouldBe 2
        getEventRepository().countAll() shouldBe 2
    }

    @Test
    fun `deleteAll should remove all events`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        val events = listOf(
            createEvent(listener.id, filterIndex = 0),
            createEvent(listener.id, filterIndex = 1)
        )
        getEventRepository().insert(events)

        getEventRepository().deleteAll()

        getEventRepository().countAll() shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should return 0 when no listeners match`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        // Query key with different workflow info
        val queryKey = ListenerQueryKey(
            workflowInfo = WorkflowInfo(
                WorkflowNamespace("non-existent"),
                WorkflowName("non-existent"),
                WorkflowVersion("0.0.0")
            ),
            position = NodePosition("/do/0/nonExistent"),
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        inserted shouldBe 0
    }

}
