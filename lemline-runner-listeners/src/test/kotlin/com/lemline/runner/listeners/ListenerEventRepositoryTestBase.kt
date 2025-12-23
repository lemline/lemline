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

    private fun createListener(hasForeach: Boolean = false): ListenerModel {
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

        val config = ListenConfig(
            strategy = ListenStrategy.ALL,
            filters = listOf(
                EventFilter(type = "com.example.Event1"),
                EventFilter(type = "com.example.Event2")
            ),
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
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null
        )
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event","data":"test"}"""

        val inserted = getEventRepository().batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        inserted shouldBe 1
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson
        events.first().eventId shouldBe eventId
    }

    @Test
    fun `batchInsertForOneAny should not insert if listener already has event`() = runTest {
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = null
        )
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

    // ========== batchInsertForAccumulating tests ==========

    @Test
    fun `batchInsertForAccumulating should insert event for matching listener`() = runTest {
        val listener = createListener(hasForeach = false)
        getListenerRepository().insert(listener)

        val queryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = 0
        )
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        val inserted = getEventRepository().batchInsertForAccumulating(listOf(queryKey), eventId, eventJson)

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

        val queryKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = 0
        )

        val inserted1 =
            getEventRepository().batchInsertForAccumulating(listOf(queryKey), "event-first", """{"first":true}""")
        val inserted2 =
            getEventRepository().batchInsertForAccumulating(listOf(queryKey), "event-second", """{"second":true}""")

        inserted1 shouldBe 1
        inserted2 shouldBe 0
        val events = getEventRepository().findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe """{"first":true}"""
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
}
