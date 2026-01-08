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
import io.kotest.matchers.nulls.shouldNotBeNull
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
            // Use auto-incrementing event_index if not explicitly provided
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
        countAllEvents() shouldBe 2
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

        countAllEvents() shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should return 0 when no listeners match`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

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

    // ========== batchInsertForOneAny tests ==========

    @Test
    fun `batchInsertForOneAny should insert event for ONE strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val inserted = getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-one-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 1
        countAllEvents() shouldBe 1
    }

    @Test
    fun `batchInsertForOneAny should insert event for ANY strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ANY, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val inserted = getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-any-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 1
        countAllEvents() shouldBe 1
    }

    @Test
    fun `batchInsertForOneAny should mark listener as completed`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-completion-123",
            """{"type":"com.example.Event"}"""
        )

        val updated = getListenerRepository().findById(listener.id, null)
        updated?.closedAt.shouldNotBeNull()
    }

    @Test
    fun `batchInsertForOneAny should set foreach_completed false when hasForeach is true`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-foreach-123",
            """{"type":"com.example.Event"}"""
        )

        val events = findAllEvents()
        events.size shouldBe 1
        events.first().foreachCompleted shouldBe false
        events.first().foreachOutput shouldBe null
    }

    @Test
    fun `batchInsertForOneAny should set foreach_completed true when hasForeach is false`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventJson = """{"type":"com.example.Event"}"""
        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-no-foreach-123", eventJson)

        val events = findAllEvents()
        events.size shouldBe 1
        events.first().foreachCompleted shouldBe true
        events.first().foreachOutput shouldBe eventJson
    }

    @Test
    fun `batchInsertForOneAny should be idempotent for duplicate events`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "duplicate-event-123"
        val eventJson = """{"type":"com.example.Event"}"""

        val first = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)
        val second = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        first shouldBe 1
        second shouldBe 0
        countAllEvents() shouldBe 1
    }

    @Test
    fun `batchInsertForOneAny should skip ALL strategy listeners`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val inserted = getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-all-skip-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 0
        countAllEvents() shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should skip closed listeners`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        listener.closedAt = Clock.System.now()
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val inserted = getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-closed-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 0
    }

    // ========== batchInsertForAllAnyUntil tests ==========

    @Test
    fun `batchInsertForAllAnyUntil should insert event for ALL strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val inserted = getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey),
            "event-all-123",
            """{"type":"com.example.Event1"}"""
        )

        inserted shouldBe 1
        countAllEvents() shouldBe 1
    }

    @Test
    fun `batchInsertForAllAnyUntil should insert events with correct event_index`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey0),
            "event-sort-0",
            """{"type":"com.example.Event1"}"""
        )
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey1),
            "event-sort-1",
            """{"type":"com.example.Event2"}"""
        )

        val events = findAllEvents().sortedBy { it.sortKey }
        events.size shouldBe 2
        events[0].sortKey shouldBe 0
        events[1].sortKey shouldBe 1
    }

    @Test
    fun `batchInsertForAllAnyUntil should mark ALL listener completed when all filters matched`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey0),
            "event-complete-0",
            """{"type":"com.example.Event1"}"""
        )

        var updated = getListenerRepository().findById(listener.id, null)
        updated?.closedAt shouldBe null

        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey1),
            "event-complete-1",
            """{"type":"com.example.Event2"}"""
        )

        updated = getListenerRepository().findById(listener.id, null)
        updated?.closedAt.shouldNotBeNull()
    }

    @Test
    fun `batchInsertForAllAnyUntil should skip ONE strategy listeners`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener).copy(listenerStrategy = ListenerStrategy.ONE)
        val inserted = getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey),
            "event-one-skip-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 0
    }

    @Test
    fun `batchInsertForAllAnyUntil should be idempotent for duplicate events`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val eventId = "duplicate-all-event-123"
        val eventJson = """{"type":"com.example.Event1"}"""

        val first = getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), eventId, eventJson)
        val second = getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), eventId, eventJson)

        first shouldBe 1
        second shouldBe 0
        countAllEvents() shouldBe 1
    }

    @Test
    fun `batchInsertForAllAnyUntil should insert for ANY_UNTIL_EXPR strategy`() = runTest {
        val listener = createListenerWithStrategy(ListenerStrategy.ANY_UNTIL_EXPR)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener).copy(listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR)
        val inserted = getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey),
            "event-until-expr-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 1
    }

    @Test
    fun `batchInsertForAllAnyUntil should insert for ANY_UNTIL_EVENT strategy`() = runTest {
        val listener = createListenerWithStrategy(ListenerStrategy.ANY_UNTIL_EVENT)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener).copy(listenerStrategy = ListenerStrategy.ANY_UNTIL_EVENT)
        val inserted = getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey),
            "event-until-event-123",
            """{"type":"com.example.Event"}"""
        )

        inserted shouldBe 1
    }

    // ========== markForeachCompleted tests ==========

    @Test
    fun `markForeachCompleted should mark event as completed with output`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-foreach-mark-123",
            """{"type":"com.example.Event"}"""
        )

        val output = """{"result":"processed"}"""
        val updated = getEventRepository().markForeachCompleted(listener.id, 0, output)

        updated shouldBe 1
        val events = findAllEvents()
        events.first().foreachCompleted shouldBe true
        events.first().foreachOutput shouldBe output
    }

    @Test
    fun `markForeachCompleted should return 0 for already completed event`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-double-complete-123",
            """{"type":"com.example.Event"}"""
        )

        getEventRepository().markForeachCompleted(listener.id, 0, """{"result":"first"}""")
        val secondUpdate = getEventRepository().markForeachCompleted(listener.id, 0, """{"result":"second"}""")

        secondUpdate shouldBe 0
    }

    @Test
    fun `markForeachCompleted should return 0 for non-existent event`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val updated = getEventRepository().markForeachCompleted(listener.id, 999, """{"result":"none"}""")

        updated shouldBe 0
    }

    @Test
    fun `markForeachCompleted should handle null output`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(
            listOf(queryKey),
            "event-null-output-123",
            """{"type":"com.example.Event"}"""
        )

        val updated = getEventRepository().markForeachCompleted(listener.id, 0, null)

        updated shouldBe 1
        val events = findAllEvents()
        events.first().foreachCompleted shouldBe true
        events.first().foreachOutput shouldBe null
    }

    // ========== findCompletedOutputsByListeners tests ==========

    @Test
    fun `findCompletedOutputsByListeners should return outputs for single listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey0),
            "output-event-0",
            """{"type":"Event1"}"""
        )
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(queryKey1),
            "output-event-1",
            """{"type":"Event2"}"""
        )

        val outputs = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        outputs.size shouldBe 1
        outputs[listener.id]?.size shouldBe 2
    }

    @Test
    fun `findCompletedOutputsByListeners should return outputs for multiple listeners`() = runTest {
        val listener1 = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        val listener2 = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)
        getListenerRepository().insert(listener2)

        val queryKey1 = createQueryKey(listener1)
        val queryKey2 = ListenerQueryKey(
            workflowInfo = listener2.instanceMessage.workflowInfo,
            position = listener2.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        getEventRepository().batchInsertForOneAny(listOf(queryKey1), "multi-event-1", """{"l":"1"}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey2), "multi-event-2", """{"l":"2"}""")

        val outputs = getEventRepository().findCompletedOutputsByListeners(listOf(listener1.id, listener2.id))

        outputs.size shouldBe 2
        outputs[listener1.id]?.size shouldBe 1
        outputs[listener2.id]?.size shouldBe 1
    }

    @Test
    fun `findCompletedOutputsByListeners should return empty map for empty input`() = runTest {
        val outputs = getEventRepository().findCompletedOutputsByListeners(emptyList())

        outputs shouldBe emptyMap()
    }

    @Test
    fun `findCompletedOutputsByListeners should return empty list for listener without events`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val outputs = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        outputs[listener.id] shouldBe null
    }

    @Test
    fun `findCompletedOutputsByListeners should order outputs by event_index`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 3)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey2 = createQueryKey(listener, filterIndex = 2).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey2), "order-2", """{"order":2}""")
        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey0), "order-0", """{"order":0}""")
        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey1), "order-1", """{"order":1}""")

        val outputs = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        outputs[listener.id]?.size shouldBe 3
        outputs[listener.id]?.get(0) shouldBe """{"order":2}"""
        outputs[listener.id]?.get(1) shouldBe """{"order":0}"""
        outputs[listener.id]?.get(2) shouldBe """{"order":1}"""
    }

    @Test
    fun `findCompletedOutputsByListeners should exclude events without foreach_output`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(listOf(queryKey), "no-output-event", """{"type":"Event"}""")

        val outputs = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        outputs[listener.id] shouldBe null
    }

    // ========== markReadyForForeach tests ==========

    @Test
    fun `markReadyForForeach should mark head event ready`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey0), "fifo-0", """{"idx":0}""")
        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey1), "fifo-1", """{"idx":1}""")

        val marked = getEventRepository().markReadyForForeach(limit = 10)

        marked shouldBe 1

        val ready = getEventRepository().findEntitiesToProcess(maxAttempts = 10, limit = 10, connection = null)
        ready.size shouldBe 1
        ready.first().sortKey shouldBe 0
    }

    @Test
    fun `markReadyForForeach should skip listeners with event already processing`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey0), "processing-0", """{"idx":0}""")
        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey1), "processing-1", """{"idx":1}""")

        getEventRepository().markReadyForForeach(limit = 10)

        val secondMark = getEventRepository().markReadyForForeach(limit = 10)

        secondMark shouldBe 0
    }

    @Test
    fun `markReadyForForeach should handle multiple listeners`() = runTest {
        val listener1 = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        val listener2 = createListenerWithDifferentPosition(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)
        getListenerRepository().insert(listener2)

        val queryKey1 = createQueryKey(listener1)
        val queryKey2 = ListenerQueryKey(
            workflowInfo = listener2.instanceMessage.workflowInfo,
            position = listener2.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        getEventRepository().batchInsertForOneAny(listOf(queryKey1), "multi-1", """{"l":"1"}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey2), "multi-2", """{"l":"2"}""")

        val marked = getEventRepository().markReadyForForeach(limit = 10)

        marked shouldBe 2
    }

    @Test
    fun `markReadyForForeach should respect limit parameter`() = runTest {
        val listener1 = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        val listener2 = createListenerWithDifferentPosition(hasForeach = true, strategy = ListenStrategy.ONE)
        val listener3 = createListenerWithThirdPosition(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)
        getListenerRepository().insert(listener2)
        getListenerRepository().insert(listener3)

        val queryKey1 = createQueryKey(listener1)
        val queryKey2 = ListenerQueryKey(
            workflowInfo = listener2.instanceMessage.workflowInfo,
            position = listener2.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )
        val queryKey3 = ListenerQueryKey(
            workflowInfo = listener3.instanceMessage.workflowInfo,
            position = listener3.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        getEventRepository().batchInsertForOneAny(listOf(queryKey1), "limit-1", """{"l":"1"}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey2), "limit-2", """{"l":"2"}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey3), "limit-3", """{"l":"3"}""")

        val marked = getEventRepository().markReadyForForeach(limit = 2)

        marked shouldBe 2
    }

    @Test
    fun `markReadyForForeach should mark next pending event after current completes`() = runTest {
        val listener1 = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        val listener2 = createListenerWithDifferentPosition(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)
        getListenerRepository().insert(listener2)

        val queryKey1 = createQueryKey(listener1)
        val queryKey2 = ListenerQueryKey(
            workflowInfo = listener2.instanceMessage.workflowInfo,
            position = listener2.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        getEventRepository().batchInsertForOneAny(listOf(queryKey1), "event-1", """{"idx":1}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey2), "event-2", """{"idx":2}""")

        val firstMark = getEventRepository().markReadyForForeach(limit = 10)
        firstMark shouldBe 2

        getEventRepository().markForeachCompleted(listener1.id, 0, """{"done":1}""")

        val secondMark = getEventRepository().markReadyForForeach(limit = 10)
        secondMark shouldBe 0
    }

    @Test
    fun `markReadyForForeach should return 0 when no pending events`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(listOf(queryKey), "no-pending", """{"type":"Event"}""")

        val marked = getEventRepository().markReadyForForeach(limit = 10)

        marked shouldBe 0
    }

    // ========== findEntitiesToProcess tests ==========

    @Test
    fun `findEntitiesToProcess should return ready events`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        getEventRepository().batchInsertForOneAny(listOf(queryKey), "ready-event", """{"type":"Event"}""")
        getEventRepository().markReadyForForeach(limit = 10)

        val entities = getEventRepository().findEntitiesToProcess(maxAttempts = 10, limit = 10, connection = null)

        entities.size shouldBe 1
    }

    @Test
    fun `findEntitiesToProcess should not return events without outbox_delayed_until`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0).copy(listenerStrategy = ListenerStrategy.ALL)
        val queryKey1 = createQueryKey(listener, filterIndex = 1).copy(listenerStrategy = ListenerStrategy.ALL)

        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey0), "not-ready-0", """{"idx":0}""")
        getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey1), "not-ready-1", """{"idx":1}""")

        val entities = getEventRepository().findEntitiesToProcess(maxAttempts = 10, limit = 10, connection = null)

        entities.size shouldBe 0
    }

    @Test
    fun `findEntitiesToProcess should respect limit parameter`() = runTest {
        val listener1 = createListener(hasForeach = true, strategy = ListenStrategy.ONE, filtersCount = 1)
        val listener2 = createListenerWithDifferentPosition(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)
        getListenerRepository().insert(listener2)

        val queryKey1 = createQueryKey(listener1)
        val queryKey2 = ListenerQueryKey(
            workflowInfo = listener2.instanceMessage.workflowInfo,
            position = listener2.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ONE
        )

        getEventRepository().batchInsertForOneAny(listOf(queryKey1), "limit-test-1", """{"l":"1"}""")
        getEventRepository().batchInsertForOneAny(listOf(queryKey2), "limit-test-2", """{"l":"2"}""")
        getEventRepository().markReadyForForeach(limit = 10)

        val entities = getEventRepository().findEntitiesToProcess(maxAttempts = 10, limit = 1, connection = null)

        entities.size shouldBe 1
    }

    // ========== Helper methods ==========

    private fun createListenerWithStrategy(strategy: ListenerStrategy): ListenerModel {
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

        val coreStrategy = when (strategy) {
            ListenerStrategy.ONE -> ListenStrategy.ONE
            ListenerStrategy.ANY, ListenerStrategy.ANY_UNTIL_EXPR, ListenerStrategy.ANY_UNTIL_EVENT -> ListenStrategy.ANY
            ListenerStrategy.ALL -> ListenStrategy.ALL
        }

        val config = ListenConfig(
            strategy = coreStrategy,
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
        ).also {
            it.outboxScheduledFor = now
            it.hasForeach = false
        }
    }

    data class EventRecord(
        val sortKey: Int,
        val foreachCompleted: Boolean,
        val foreachOutput: String?
    )

    private suspend fun findAllEvents(): List<EventRecord> {
        val results = mutableListOf<EventRecord>()
        getDatabaseConfig().withConnection { conn ->
            conn.prepareStatement(
                "SELECT event_index, foreach_completed, foreach_output FROM $LISTENER_EVENTS_TABLE ORDER BY event_index"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(
                            EventRecord(
                                sortKey = rs.getInt("event_index"),
                                foreachCompleted = rs.getBoolean("foreach_completed"),
                                foreachOutput = rs.getString("foreach_output")
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    private suspend fun countAllEvents(): Long {
        return getDatabaseConfig().withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $LISTENER_EVENTS_TABLE").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }
}
