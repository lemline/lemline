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
    private val testNodePosition = NodePosition("/do/listenTask")
    private val testNodePosition2 = NodePosition("/do/listenTask2")
    private val testNodePosition3 = NodePosition("/do/listenTask3")

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

        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                testNodePosition to TaskState(startedAt = now)
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

        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                position to TaskState(startedAt = now)
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
        eventId: String = "event-${eventIdCounter++}"
    ): ListenerEventModel {
        return ListenerEventModel(
            listenerId = listenerId,
            eventId = eventId,
            filterIndex = filterIndex,
            event = eventData,
            outboxScheduledFor = Clock.System.now()
        )
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
        listener.completedAt = Clock.System.now() // Mark as completed (stops collecting events)
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
            position = NodePosition("/do/nonExistent"),
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

        val queryKey = createQueryKey(listener, filterIndex = 5) // Even if filterIndex is specified, ONE/ANY should use 0

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().filterIndex shouldBe 0 // Should always be 0 for ONE/ANY
    }

    @Test
    fun `batchInsertForOneAny should only match listeners still collecting events in batch`() = runTest {
        val activeListener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        val completedListener = createListenerWithDifferentPosition(hasForeach = false, strategy = ListenStrategy.ONE)
        completedListener.completedAt = Clock.System.now() // Stopped collecting events
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
    }

    @Test
    fun `markReadyForForeach should return 0 when no pending events exist`() = runTest {
        val marked = getEventRepository().markReadyForForeach(limit = 100)
        marked shouldBe 0
    }

    // ========== Listener completion tests ==========

    @Test
    fun `batchInsertForOneAny should set completed_at on ONE strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify completed_at is set
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.completedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForOneAny should set completed_at on ANY strategy listener`() = runTest {
        val listener = createListener(hasForeach = false, strategy = ListenStrategy.ANY)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify completed_at is set
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.completedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForOneAny should set completed_at on foreach listener too`() = runTest {
        val listener = createListener(hasForeach = true, strategy = ListenStrategy.ONE)
        getListenerRepository().insert(listener)

        val queryKey = createQueryKey(listener)

        getEventRepository().batchInsertForOneAny(listOf(queryKey), "event-id", """{"data":"test"}""")

        // Reload listener and verify completed_at is set (even for foreach)
        val reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.completedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForAccumulating should set completed_at on ALL strategy when all filters matched`() = runTest {
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
        reloadedListener!!.completedAt shouldBe null

        // Insert event for filter 1
        getEventRepository().batchInsertForAllAnyUntil(
            listOf(baseQueryKey.copy(filterIndex = 1)),
            "event-filter-1",
            """{"filter":1}"""
        )

        // Now listener should be completed (both filters matched)
        reloadedListener = getListenerRepository().findById(listener.id)
        reloadedListener shouldNotBe null
        reloadedListener!!.completedAt shouldNotBe null
    }

    @Test
    fun `batchInsertForAccumulating should not set completed_at when not all filters matched`() = runTest {
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
        reloadedListener!!.completedAt shouldBe null
    }
}
