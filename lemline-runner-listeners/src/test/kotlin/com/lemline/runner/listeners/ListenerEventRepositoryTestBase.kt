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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
                StackFrame(NodePosition.root, RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                )),
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
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
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
                StackFrame(NodePosition.root, RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                )),
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
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
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
    fun `insert and findByListenerId should work`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        val event = createEvent(listener.id, filterIndex = 0)
        getEventRepository().insert(event)

        val found = getEventRepository().findByListenerId(listener.id)

        found shouldHaveSize 1
        found.first().listenerId shouldBe event.listenerId
        found.first().eventId shouldBe event.eventId
        found.first().filterIndex shouldBe event.filterIndex
        found.first().event shouldBe event.event
    }

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

    // ========== findByListenerId tests ==========

    @Test
    fun `findByListenerId should return empty list for non-existent listener`() = runTest {
        val result = getEventRepository().findByListenerId(IDV7.random())
        result.shouldBeEmpty()
    }

    @Test
    fun `findByListenerId should return events ordered by created_at`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        val event0 = createEvent(listener.id, filterIndex = 2, eventData = """{"order":0}""", eventId = "event-0")
        val event1 = createEvent(listener.id, filterIndex = 0, eventData = """{"order":1}""", eventId = "event-1")
        val event2 = createEvent(listener.id, filterIndex = 1, eventData = """{"order":2}""", eventId = "event-2")
        getEventRepository().insert(listOf(event0, event1, event2))

        val result = getEventRepository().findByListenerId(listener.id)

        result shouldHaveSize 3
        result.map { it.eventId } shouldContainExactly listOf("event-0", "event-1", "event-2")
    }

    @Test
    fun `findByListenerId should only return events for specified listener`() = runTest {
        val listener1 = createListener()
        val listener2 = createListener()
        getListenerRepository().insert(listOf(listener1, listener2))

        val events1 = listOf(
            createEvent(listener1.id, filterIndex = 0, eventData = """{"listener":1}"""),
            createEvent(listener1.id, filterIndex = 1, eventData = """{"listener":1}""")
        )
        val events2 = listOf(
            createEvent(listener2.id, filterIndex = 0, eventData = """{"listener":2}""")
        )
        getEventRepository().insert(events1 + events2)

        val result1 = getEventRepository().findByListenerId(listener1.id)
        val result2 = getEventRepository().findByListenerId(listener2.id)

        result1 shouldHaveSize 2
        result1.all { it.listenerId == listener1.id } shouldBe true

        result2 shouldHaveSize 1
        result2.first().listenerId shouldBe listener2.id
    }

    // ========== countByListenerId tests ==========

    @Test
    fun `countByListenerId should return 0 for non-existent listener`() = runTest {
        val count = getEventRepository().countByListenerId(IDV7.random())
        count shouldBe 0
    }

    @Test
    fun `countByListenerId should return correct count`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        val events = (0..2).map { createEvent(listener.id, filterIndex = it) }
        getEventRepository().insert(events)

        val count = getEventRepository().countByListenerId(listener.id)

        count shouldBe 3
    }

    // ========== CASCADE delete tests ==========

    @Test
    fun `deleting listener should cascade delete its events`() = runTest {
        val listener = createListener()
        getListenerRepository().insert(listener)

        getEventRepository().insert(
            listOf(
                createEvent(listener.id, filterIndex = 0),
                createEvent(listener.id, filterIndex = 1)
            )
        )

        getEventRepository().countByListenerId(listener.id) shouldBe 2

        getListenerRepository().deleteById(listener.id)

        getEventRepository().countByListenerId(listener.id) shouldBe 0
        getEventRepository().findByListenerId(listener.id).shouldBeEmpty()
    }

    // ========== batchInsertForOneAny tests ==========

    @Test
    fun `batchInsertForOneAny should insert event for matching listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event","data":"test"}"""

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        inserted shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson
        events.first().eventId shouldBe eventId
        events.first().filterIndex shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should not insert if listener already has event`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId1 = "ce-first"
        val eventJson1 = """{"type":"com.example.Event1"}"""
        getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId1, eventJson1)

        val eventId2 = "ce-second"
        val eventJson2 = """{"type":"com.example.Event2"}"""
        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId2, eventJson2)

        inserted shouldBe 0
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson1
    }

    @Test
    fun `batchInsertForOneAny should return 0 for empty keys list`() = runTest {
        val inserted = getEventRepository().batchInsertForOneAny(emptyList(), "event-id", """{"data":"test"}""")
        inserted shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should set foreach fields correctly when hasForeach is true`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-foreach-test"
        val eventJson = """{"type":"com.example.Event"}"""

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        inserted shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        val event = events.first()
        event.foreachCompleted shouldBe false
        event.foreachOutput shouldBe null
        event.outboxScheduledFor shouldNotBe null // Should be set for foreach processing
    }

    @Test
    fun `batchInsertForOneAny should set foreach fields correctly when hasForeach is false`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-no-foreach-test"
        val eventJson = """{"type":"com.example.Event"}"""

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        inserted shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        val event = events.first()
        event.foreachCompleted shouldBe true
        event.foreachOutput shouldBe eventJson // Output is the event itself
    }

    @Test
    fun `batchInsertForOneAny should not match completed listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        listener.closedAt = Clock.System.now() // Mark as completed (stops collecting events)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        inserted shouldBe 0
        getEventRepository().findByListenerId(listener.id).shouldBeEmpty()
    }

    @Test
    fun `batchInsertForOneAny should insert for multiple listeners with different query keys`() = runTest {
        val listener1 = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        val listener2 = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listOf(listener1, listener2))

        val queryKeys = listOf(
            createQueryKey(listener1),
            createQueryKey(listener2)
        )

        val inserted = getEventRepository().batchInsertForOneAny(queryKeys, "batch-event", """{"data":"batch"}""")

        inserted shouldBe 2
        getEventRepository().findByListenerId(listener1.id) shouldHaveSize 1
        getEventRepository().findByListenerId(listener2.id) shouldHaveSize 1
    }

    @Test
    fun `batchInsertForOneAny should insert once when multiple keys match same listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        // Same query key duplicated
        val queryKey = createQueryKey(listener)

        val inserted =
            getEventRepository().batchInsertForOneAny(listOf(queryKey, queryKey), "event-id", """{"data":"test"}""")

        inserted shouldBe 1
        getEventRepository().findByListenerId(listener.id) shouldHaveSize 1
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

    @Test
    fun `batchInsertForOneAny should be idempotent with same eventId`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "idempotent-event-id"
        val eventJson = """{"type":"com.example.Event"}"""

        // First insert
        val inserted1 = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)
        inserted1 shouldBe 1

        // Second insert with same eventId - should be ignored due to UNIQUE(listener_id, filter_index)
        val inserted2 = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)
        inserted2 shouldBe 0

        // Only one event should exist
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().eventId shouldBe eventId
    }

    @Test
    fun `batchInsertForOneAny should always use filterIndex 0`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey =
            createQueryKey(listener, filterIndex = 5) // Even if filterIndex is specified, ONE/ANY should use 0

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().filterIndex shouldBe 0 // Should always be 0 for ONE/ANY
    }

    @Test
    fun `batchInsertForOneAny should only match listeners still collecting events in batch`() = runTest {
        val activeListener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        val completedListener = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        completedListener.closedAt = Clock.System.now() // Stopped collecting events
        getListenerRepository().insert(listOf(activeListener, completedListener))

        val queryKeys = listOf(
            createQueryKey(activeListener),
            createQueryKey(completedListener)
        )

        val inserted = getEventRepository().batchInsertForOneAny(queryKeys, "event-id", """{"data":"test"}""")

        inserted shouldBe 1
        getEventRepository().findByListenerId(activeListener.id) shouldHaveSize 1
        getEventRepository().findByListenerId(completedListener.id).shouldBeEmpty()
    }

    // ========== batchInsertForAccumulating tests ==========

    @Test
    fun `batchInsertForAccumulating should insert event for matching listener`() = runTest {
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        val inserted = getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), eventId, eventJson)

        inserted shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson
        events.first().filterIndex shouldBe 0
        events.first().eventId shouldBe eventId
    }

    @Test
    fun `batchInsertForAccumulating should handle filterIndex uniqueness for ALL strategy`() = runTest {
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)

        val inserted1 =
            getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), "event-first", """{"first":true}""")
        val inserted2 =
            getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), "event-second", """{"second":true}""")

        inserted1 shouldBe 1
        inserted2 shouldBe 0
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe """{"first":true}"""
    }

    @Test
    fun `batchInsertForAccumulating should be idempotent with same eventId and filterIndex`() = runTest {
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "idempotent-event-id"
        val eventJson = """{"type":"com.example.Event"}"""

        // First insert
        val inserted1 = getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), eventId, eventJson)
        inserted1 shouldBe 1

        // Second insert with same eventId and filterIndex - should be ignored
        val inserted2 = getEventRepository().batchInsertForAllAnyUntil(listOf(queryKey), eventId, eventJson)
        inserted2 shouldBe 0

        // Only one event should exist
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().eventId shouldBe eventId
    }

    // ========== markReadyForForeach tests ==========

    @Test
    fun `markReadyForForeach should mark single pending event as ready`() = runTest {
        val listener = createListener(hasForeach = true)
        getListenerRepository().insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "pending-event")
        getEventRepository().insert(event)

        val marked = getEventRepository().markReadyForForeach(limit = 100)

        marked shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().outboxDelayedUntil shouldNotBe null
        events.first().outboxScheduledFor shouldNotBe null // Also set by markReadyForForeach
    }

    @Test
    fun `markReadyForForeach should return 0 when no pending events exist`() = runTest {
        val marked = getEventRepository().markReadyForForeach(limit = 100)
        marked shouldBe 0
    }

    @Test
    fun `markReadyForForeach should mark same event_id only once when it matches multiple filters`() = runTest {
        // Scenario: ALL strategy with foreach, a single CloudEvent matches multiple filters
        // This creates multiple rows with the same event_id but different filter_index
        // The same event should only be processed ONCE through foreach.do, not once per filter
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        // Insert two rows with the SAME event_id but different filter_index
        // This simulates a single CloudEvent matching both filters
        val sameEventId = "single-cloud-event"
        val event0 =
            createEvent(listener.id, filterIndex = 0, eventId = sameEventId, eventData = """{"type":"Event"}""")
        val event1 =
            createEvent(listener.id, filterIndex = 1, eventId = sameEventId, eventData = """{"type":"Event"}""")
        getEventRepository().insert(listOf(event0, event1))

        // First call should mark exactly ONE row as ready (the one with lower sort_key)
        val marked1 = getEventRepository().markReadyForForeach(limit = 100)
        marked1 shouldBe 1

        val eventsAfterFirstMark = getEventRepository().findByListenerId(listener.id)
        val readyEvents = eventsAfterFirstMark.filter { it.outboxDelayedUntil != null }
        readyEvents shouldHaveSize 1

        // Simulate completing the first event's foreach processing
        // IMPORTANT: markCompletedWithOutputByWorkflow should mark ALL rows with the same event_id as completed
        val readyEvent = readyEvents.first()
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = readyEvent.eventId,
            output = """{"result":"done"}"""
        )

        // Verify that BOTH rows are now marked as foreach_completed
        // (since markCompletedWithOutputByWorkflow uses event_id without filter_index)
        val allEvents = getEventRepository().findByListenerId(listener.id)
        val completedEvents = allEvents.filter { it.foreachCompleted }
        completedEvents shouldHaveSize 2 // Both rows should be completed
        completedEvents.all { it.eventId == sameEventId } shouldBe true

        // Second call should NOT mark another event as ready
        val marked2 = getEventRepository().markReadyForForeach(limit = 100)
        marked2 shouldBe 0
    }

    @Test
    fun `markReadyForForeach should not mark second row while first row with same event_id is processing`() = runTest {
        // This test verifies that while one row is being processed (has outbox_delayed_until set),
        // other rows with the same event_id are blocked from being marked ready
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val sameEventId = "single-cloud-event"
        val event0 = createEvent(listener.id, filterIndex = 0, eventId = sameEventId)
        val event1 = createEvent(listener.id, filterIndex = 1, eventId = sameEventId)
        getEventRepository().insert(listOf(event0, event1))

        // Mark first row as ready
        val marked1 = getEventRepository().markReadyForForeach(limit = 100)
        marked1 shouldBe 1

        // Without calling markCompletedWithOutput (simulating in-progress processing),
        // the second row should NOT be marked as ready
        val marked2 = getEventRepository().markReadyForForeach(limit = 100)
        marked2 shouldBe 0 // Blocked by NOT EXISTS check (first row has outbox_delayed_until set)

        // Verify only one row has outbox_delayed_until set
        val allEvents = getEventRepository().findByListenerId(listener.id)
        val readyEvents = allEvents.filter { it.outboxDelayedUntil != null }
        readyEvents shouldHaveSize 1
    }

    @Test
    fun `markReadyForForeach should block second row even after outbox processes first row`() = runTest {
        // Full flow test: simulate outbox processing the first row (sets outbox_completed_at)
        // and verify the second row with same event_id is still blocked
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val sameEventId = "single-cloud-event"
        val event0 = createEvent(listener.id, filterIndex = 0, eventId = sameEventId)
        val event1 = createEvent(listener.id, filterIndex = 1, eventId = sameEventId)
        getEventRepository().insert(listOf(event0, event1))

        // Step 1: markReadyForForeach marks row 0
        val marked1 = getEventRepository().markReadyForForeach(limit = 100)
        marked1 shouldBe 1

        // Step 2: Simulate outbox picking up and processing row 0
        // The outbox would update: outbox_delayed_until = far_future, outbox_completed_at = NOW
        // We simulate this by calling findEntitiesToProcess (which uses FOR UPDATE SKIP LOCKED)
        val entities = getEventRepository().findEntitiesToProcess(maxAttempts = 3, limit = 10, connection = null)
        entities shouldHaveSize 1
        entities.first().eventId shouldBe sameEventId
        entities.first().filterIndex shouldBe 0

        // Step 3: Call markReadyForForeach again - row 1 should still be blocked
        // because row 0 has foreach_completed=FALSE and outbox_delayed_until IS NOT NULL
        val marked2 = getEventRepository().markReadyForForeach(limit = 100)
        marked2 shouldBe 0 // Expected: 0 (blocked)

        // Step 4: Only after markCompletedWithOutputByWorkflow should row 1 be unblocked
        // But since markCompletedWithOutputByWorkflow updates ALL rows with the same event_id,
        // row 1 will also be marked as completed and won't need processing
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = sameEventId,
            output = """{"result":"done"}"""
        )

        // Verify BOTH rows are now completed
        val allEvents = getEventRepository().findByListenerId(listener.id)
        allEvents.all { it.foreachCompleted } shouldBe true

        // And no more rows to mark
        val marked3 = getEventRepository().markReadyForForeach(limit = 100)
        marked3 shouldBe 0
    }

    @Test
    fun `markReadyForForeach should process different event_ids independently`() = runTest {
        // This test verifies that different event_ids are processed correctly
        // When we have rows with DIFFERENT event_ids, they should be processed one at a time (FIFO)
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        // Insert rows with DIFFERENT event_ids
        val eventA = createEvent(listener.id, filterIndex = 0, eventId = "event-A")
        val eventB = createEvent(listener.id, filterIndex = 1, eventId = "event-B")
        getEventRepository().insert(listOf(eventA, eventB))

        // First call marks event-A as ready (lower sort_key)
        val marked1 = getEventRepository().markReadyForForeach(limit = 100)
        marked1 shouldBe 1

        // While event-A is processing, event-B should be blocked (FIFO per listener)
        val marked2 = getEventRepository().markReadyForForeach(limit = 100)
        marked2 shouldBe 0

        // Complete event-A
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = "event-A",
            output = """{"result":"A"}"""
        )

        // Now event-B should be marked as ready
        val marked3 = getEventRepository().markReadyForForeach(limit = 100)
        marked3 shouldBe 1

        val allEvents = getEventRepository().findByListenerId(listener.id)
        val readyEvents = allEvents.filter { it.outboxDelayedUntil != null && !it.foreachCompleted }
        readyEvents shouldHaveSize 1
        readyEvents.first().eventId shouldBe "event-B"
    }

    @Test
    fun `markReadyForForeach should respect limit parameter`() = runTest {
        // Create multiple listeners, each with a pending event
        val listener1 = createListener(hasForeach = true)
        val listener2 = createListenerWithDifferentPosition(hasForeach = true)
        val listener3 = createListenerWithThirdPosition(hasForeach = true)
        getListenerRepository().insert(listOf(listener1, listener2, listener3))

        // Insert one event per listener
        val event1 = createEvent(listener1.id, filterIndex = 0, eventId = "event-1")
        val event2 = createEvent(listener2.id, filterIndex = 0, eventId = "event-2")
        val event3 = createEvent(listener3.id, filterIndex = 0, eventId = "event-3")
        getEventRepository().insert(listOf(event1, event2, event3))

        // With limit=2, only 2 listeners should have events marked
        val marked = getEventRepository().markReadyForForeach(limit = 2)
        marked shouldBe 2

        // Verify only 2 events have outboxDelayedUntil set
        val allEvents = listOf(
            getEventRepository().findByListenerId(listener1.id),
            getEventRepository().findByListenerId(listener2.id),
            getEventRepository().findByListenerId(listener3.id)
        ).flatten()
        val readyEvents = allEvents.filter { it.outboxDelayedUntil != null }
        readyEvents shouldHaveSize 2
    }

    @Test
    fun `markReadyForForeach should mark one event per listener when multiple listeners have pending events`() =
        runTest {
            // Create multiple listeners
            val listener1 = createListener(hasForeach = true)
            val listener2 = createListenerWithDifferentPosition(hasForeach = true)
            getListenerRepository().insert(listOf(listener1, listener2))

            // Insert multiple events per listener
            val events1 = listOf(
                createEvent(listener1.id, filterIndex = 0, eventId = "l1-event-1"),
                createEvent(listener1.id, filterIndex = 1, eventId = "l1-event-2")
            )
            val events2 = listOf(
                createEvent(listener2.id, filterIndex = 0, eventId = "l2-event-1"),
                createEvent(listener2.id, filterIndex = 1, eventId = "l2-event-2")
            )
            getEventRepository().insert(events1 + events2)

            // Should mark exactly one event per listener (2 total)
            val marked = getEventRepository().markReadyForForeach(limit = 100)
            marked shouldBe 2

            // Verify each listener has exactly one event marked
            val listener1Events = getEventRepository().findByListenerId(listener1.id)
            val listener2Events = getEventRepository().findByListenerId(listener2.id)

            listener1Events.filter { it.outboxDelayedUntil != null } shouldHaveSize 1
            listener2Events.filter { it.outboxDelayedUntil != null } shouldHaveSize 1
        }

    @Test
    fun `markReadyForForeach should respect FIFO ordering by sort_key`() = runTest {
        val listener = createListener(hasForeach = true)
        getListenerRepository().insert(listener)

        // Insert events - they will have increasing sort_key values
        val eventFirst = createEvent(listener.id, filterIndex = 0, eventId = "first-event")
        val eventSecond = createEvent(listener.id, filterIndex = 1, eventId = "second-event")
        val eventThird = createEvent(listener.id, filterIndex = 2, eventId = "third-event")
        getEventRepository().insert(listOf(eventFirst, eventSecond, eventThird))

        // First mark should select the event with lowest sort_key (first inserted)
        getEventRepository().markReadyForForeach(limit = 100)

        val events = getEventRepository().findByListenerId(listener.id)
        val readyEvent = events.first { it.outboxDelayedUntil != null }
        readyEvent.eventId shouldBe "first-event"

        // Complete first event
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = "first-event",
            output = """{}"""
        )

        // Second mark should select the next event (second inserted)
        getEventRepository().markReadyForForeach(limit = 100)

        val eventsAfter = getEventRepository().findByListenerId(listener.id)
        val nextReadyEvent = eventsAfter.first { it.outboxDelayedUntil != null && !it.foreachCompleted }
        nextReadyEvent.eventId shouldBe "second-event"
    }

    @Test
    fun `markReadyForForeach should skip already completed events`() = runTest {
        val listener = createListener(hasForeach = true)
        getListenerRepository().insert(listener)

        // Insert an event and mark it as already completed
        val completedEvent = createEvent(listener.id, filterIndex = 0, eventId = "completed-event")
        getEventRepository().insert(completedEvent)
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = "completed-event",
            output = """{"done":true}"""
        )

        // Insert a pending event
        val pendingEvent = createEvent(listener.id, filterIndex = 1, eventId = "pending-event")
        getEventRepository().insert(pendingEvent)

        // markReadyForForeach should only mark the pending event
        val marked = getEventRepository().markReadyForForeach(limit = 100)
        marked shouldBe 1

        val events = getEventRepository().findByListenerId(listener.id)
        val readyEvent = events.first { it.outboxDelayedUntil != null && !it.foreachCompleted }
        readyEvent.eventId shouldBe "pending-event"
    }

    @Test
    fun `markReadyForForeach should skip non-foreach listeners`() = runTest {
        // Create a listener WITHOUT foreach (hasForeach = false)
        val nonForeachListener = createListener(hasForeach = false)
        getListenerRepository().insert(nonForeachListener)

        // Insert event - for non-foreach listeners, events are inserted with foreach_completed = true
        // So we manually insert one with foreach_completed = false to test the skip logic
        val event = createEvent(nonForeachListener.id, filterIndex = 0, eventId = "event")
        getEventRepository().insert(event)

        // Create a listener WITH foreach
        val foreachListener = createListenerWithDifferentPosition(hasForeach = true)
        getListenerRepository().insert(foreachListener)

        val foreachEvent = createEvent(foreachListener.id, filterIndex = 0, eventId = "foreach-event")
        getEventRepository().insert(foreachEvent)

        // markReadyForForeach should mark events for both listeners that have pending events
        val marked = getEventRepository().markReadyForForeach(limit = 100)

        // Both listeners have pending events with foreach_completed = false
        marked shouldBe 2
    }

    @Test
    fun `markReadyForForeach should handle listener with no pending events`() = runTest {
        // Create a listener with only completed events
        val listener = createListener(hasForeach = true)
        getListenerRepository().insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "event")
        getEventRepository().insert(event)
        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = "event",
            output = """{}"""
        )

        // Create another listener with pending events
        val listener2 = createListenerWithDifferentPosition(hasForeach = true)
        getListenerRepository().insert(listener2)

        val event2 = createEvent(listener2.id, filterIndex = 0, eventId = "event-2")
        getEventRepository().insert(event2)

        // Should only mark the pending event from listener2
        val marked = getEventRepository().markReadyForForeach(limit = 100)
        marked shouldBe 1

        val events2 = getEventRepository().findByListenerId(listener2.id)
        events2.first().outboxDelayedUntil shouldNotBe null
    }

    @Test
    fun `markReadyForForeach should not mark events for listener already processing an event`() = runTest {
        val listener = createListener(hasForeach = true)
        getListenerRepository().insert(listener)

        // Insert first event and mark it as ready (simulating in-progress)
        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        getEventRepository().insert(event1)
        getEventRepository().markReadyForForeach(limit = 100)

        // Insert second event
        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "event-2")
        getEventRepository().insert(event2)

        // Second markReadyForForeach should not mark event2 (listener is blocked)
        val marked = getEventRepository().markReadyForForeach(limit = 100)
        marked shouldBe 0

        val events = getEventRepository().findByListenerId(listener.id)
        val readyEvents = events.filter { it.outboxDelayedUntil != null }
        readyEvents shouldHaveSize 1
        readyEvents.first().eventId shouldBe "event-1"
    }

    @Test
    fun `markReadyForForeach should mark events with NULL filter_index (ANY_UNTIL strategies)`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ANY).copy(
            listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
        ).apply {
            hasUntil = true
            untilExpression = ".value > 100"
        }
        getListenerRepository().insert(listener)

        val event1 = createEvent(listener.id, eventId = "event-1").copy(filterIndex = null).apply { sortKey = 0 }
        val event2 = createEvent(listener.id, eventId = "event-2").copy(filterIndex = null).apply { sortKey = 1 }
        val event3 = createEvent(listener.id, eventId = "event-3").copy(filterIndex = null).apply { sortKey = 2 }
        getEventRepository().insert(listOf(event1, event2, event3))

        val marked1 = getEventRepository().markReadyForForeach(limit = 100)
        marked1 shouldBe 1

        val eventsAfterFirstMark = getEventRepository().findByListenerId(listener.id)
        val readyEvents = eventsAfterFirstMark.filter { it.outboxDelayedUntil != null }
        readyEvents shouldHaveSize 1
        readyEvents.first().eventId shouldBe "event-1"

        getEventRepository().markForeachCompleted(
            workflowId = listener.instanceMessage.workflowId,
            position = listener.instanceMessage.workflowState.nodePosition,
            eventId = "event-1",
            output = """{"result":"done"}"""
        )

        val marked2 = getEventRepository().markReadyForForeach(limit = 100)
        marked2 shouldBe 1

        val eventsAfterSecondMark = getEventRepository().findByListenerId(listener.id)
        val readyEventsAfterSecond = eventsAfterSecondMark.filter { it.outboxDelayedUntil != null && !it.foreachCompleted }
        readyEventsAfterSecond shouldHaveSize 1
        readyEventsAfterSecond.first().eventId shouldBe "event-2"
    }

    @Test
    fun `update should work with NULL filter_index (critical for outbox processing)`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ANY).copy(
            listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
        ).apply {
            hasUntil = true
            untilExpression = ".value > 100"
        }
        getListenerRepository().insert(listener)

        val event = createEvent(listener.id, eventId = "event-with-null-filter").copy(filterIndex = null)
        getEventRepository().insert(event)

        getEventRepository().markReadyForForeach(limit = 100)

        val entities = getEventRepository().findEntitiesToProcess(maxAttempts = 3, limit = 10, connection = null)
        entities shouldHaveSize 1
        entities.first().filterIndex shouldBe null

        val entity = entities.first()
        entity.outboxCompletedAt = Clock.System.now()
        entity.outboxAttemptCount = 1

        val updated = getEventRepository().update(entity)
        updated shouldBe 1

        val reloaded = getEventRepository().findByListenerId(listener.id)
        reloaded shouldHaveSize 1
        reloaded.first().outboxCompletedAt shouldNotBe null
        reloaded.first().outboxAttemptCount shouldBe 1
    }

    // ========== Listener completion tests ==========

    @Test
    fun `batchInsertForOneAny should set closed_at on ONE strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify completed_at is set
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForOneAny should set closed_at on ANY strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ANY)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify completed_at is set
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForOneAny should set closed_at on foreach listener too`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify closed_at is set (even for foreach)
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForAccumulating should set closed_at on ALL strategy when all filters matched`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 2)
        getListenerRepository().insert(listener)

        val baseQueryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ALL
        )

        // Insert event for filter 0
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(baseQueryKey.copy(filterIndex = 0)),
            "event-filter-0",
            """{"filter":0}"""
        )

        // Listener should NOT be completed yet (only 1 of 2 filters matched)
        var reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldBe null

        // Insert event for filter 1
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(baseQueryKey.copy(filterIndex = 1)),
            "event-filter-1",
            """{"filter":1}"""
        )

        // Now listener should be completed (both filters matched)
        reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForAccumulating should not set closed_at when not all filters matched`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL, filtersCount = 3)
        getListenerRepository().insert(listener)

        val baseQueryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null,
            listenerStrategy = ListenerStrategy.ALL
        )

        // Insert events for only 2 of 3 filters
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(baseQueryKey.copy(filterIndex = 0), baseQueryKey.copy(filterIndex = 1)),
            "event-partial",
            """{"partial":true}"""
        )

        // Listener should NOT be completed (only 2 of 3 filters matched)
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.closedAt shouldBe null
    }

    @Test
    fun `findCompletedOutputsByListeners should return empty map for empty list`() = runTest {
        val result = getEventRepository().findCompletedOutputsByListeners(emptyList())
        result shouldBe emptyMap()
    }

    @Test
    fun `findCompletedOutputsByListeners should return outputs for single non-foreach listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        event1.foreachOutput = """{"data":"event1"}"""
        getEventRepository().insert(event1)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        result.size shouldBe 1
        result[listener.id] shouldNotBe null
        result[listener.id]!! shouldContainExactly listOf("""{"data":"event1"}""")
    }

    @Test
    fun `findCompletedOutputsByListeners should return multiple outputs ordered by sort_key`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ALL)
        getListenerRepository().insert(listener)

        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        event1.foreachOutput = """{"order":1}"""
        getEventRepository().insert(event1)

        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "event-2")
        event2.foreachOutput = """{"order":2}"""
        getEventRepository().insert(event2)

        val event3 = createEvent(listener.id, filterIndex = 2, eventId = "event-3")
        event3.foreachOutput = """{"order":3}"""
        getEventRepository().insert(event3)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        result.size shouldBe 1
        result[listener.id]!! shouldContainExactly listOf(
            """{"order":1}""",
            """{"order":2}""",
            """{"order":3}"""
        )
    }

    @Test
    fun `findCompletedOutputsByListeners should handle multiple listeners`() = runTest {
        val listener1 = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)

        val listener2 = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener2)

        val event1 = createEvent(listener1.id, filterIndex = 0, eventId = "event-1")
        event1.foreachOutput = """{"listener":"1"}"""
        getEventRepository().insert(event1)

        val event2 = createEvent(listener2.id, filterIndex = 0, eventId = "event-2")
        event2.foreachOutput = """{"listener":"2"}"""
        getEventRepository().insert(event2)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener1.id, listener2.id))

        result.size shouldBe 2
        result[listener1.id]!! shouldContainExactly listOf("""{"listener":"1"}""")
        result[listener2.id]!! shouldContainExactly listOf("""{"listener":"2"}""")
    }

    @Test
    fun `findCompletedOutputsByListeners should exclude events without foreach_output`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ALL)
        getListenerRepository().insert(listener)

        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        event1.foreachOutput = """{"completed":true}"""
        getEventRepository().insert(event1)

        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "event-2")
        event2.foreachOutput = null
        getEventRepository().insert(event2)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        result.size shouldBe 1
        result[listener.id]!! shouldHaveSize 1
        result[listener.id]!! shouldContainExactly listOf("""{"completed":true}""")
    }

    @Test
    fun `findCompletedOutputsByListeners should return empty list for listener with no completed events`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        event.foreachOutput = null
        getEventRepository().insert(event)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener.id))

        result shouldBe emptyMap()
    }

    @Test
    fun `findCompletedOutputsByListeners should handle mixed foreach and non-foreach listeners`() = runTest {
        val foreachListener = createListener(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(foreachListener)

        val nonForeachListener = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(nonForeachListener)

        val foreachEvent = createEvent(foreachListener.id, filterIndex = 0, eventId = "foreach-event")
        foreachEvent.foreachOutput = """{"type":"foreach-result"}"""
        getEventRepository().insert(foreachEvent)

        val nonForeachEvent = createEvent(nonForeachListener.id, filterIndex = 0, eventId = "non-foreach-event")
        nonForeachEvent.foreachOutput = """{"type":"cloudEvent","data":"test"}"""
        getEventRepository().insert(nonForeachEvent)

        val result = getEventRepository().findCompletedOutputsByListeners(
            listOf(foreachListener.id, nonForeachListener.id)
        )

        result.size shouldBe 2
        result[foreachListener.id]!! shouldContainExactly listOf("""{"type":"foreach-result"}""")
        result[nonForeachListener.id]!! shouldContainExactly listOf("""{"type":"cloudEvent","data":"test"}""")
    }

    @Test
    fun `findCompletedOutputsByListeners should query only requested listeners`() = runTest {
        val listener1 = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener1)

        val listener2 = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener2)

        val event1 = createEvent(listener1.id, filterIndex = 0, eventId = "event-1")
        event1.foreachOutput = """{"listener":"1"}"""
        getEventRepository().insert(event1)

        val event2 = createEvent(listener2.id, filterIndex = 0, eventId = "event-2")
        event2.foreachOutput = """{"listener":"2"}"""
        getEventRepository().insert(event2)

        val result = getEventRepository().findCompletedOutputsByListeners(listOf(listener1.id))

        result.size shouldBe 1
        result[listener1.id]!! shouldContainExactly listOf("""{"listener":"1"}""")
        result.containsKey(listener2.id) shouldBe false
    }
}
