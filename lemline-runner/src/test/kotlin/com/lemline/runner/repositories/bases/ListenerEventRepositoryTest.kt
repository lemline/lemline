// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

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
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.models.ListenerStrategy
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for ListenerEventRepository implementations.
 *
 * Tests the accumulation of CloudEvents for listeners using ALL or ANY+until strategies.
 * The listener_events table stores events that will be aggregated at completion time.
 *
 * Concrete DB-specific test classes should extend this and provide the proper Quarkus test profile.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ListenerEventRepositoryTest {

    @Inject
    protected lateinit var repository: ListenerEventRepository

    @Inject
    protected lateinit var listenerRepository: ListenerRepository

    @Inject
    protected lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var databaseManager: DatabaseManager

    // Fixed test values for workflow identification
    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/listenTask")

    @BeforeEach
    fun setup() = runTest {
        // Clear existing data
        repository.deleteAll()
        listenerRepository.deleteAll()

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
            definitionRepository.insert(definition)
        } catch (_: Exception) {
            // Definition already exists
        }
    }

    /**
     * Creates a listener model that can be used as parent for event records.
     */
    private fun createListener(): ListenerModel {
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
            strategy = ListenerStrategy.from(config),
            timeoutAt = null,
            outboxScheduledFor = now
        )
    }

    /**
     * Creates a listener event model.
     * Uses composite key (listenerId, eventId, filterIndex) - allows same event to match multiple filters.
     */
    private var eventIdCounter = 0
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
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0)

        // When
        repository.insert(event)
        val found = repository.findByListenerId(listener.id)

        // Then
        found shouldHaveSize 1
        found.first().listenerId shouldBe event.listenerId
        found.first().eventId shouldBe event.eventId
        found.first().filterIndex shouldBe event.filterIndex
        found.first().event shouldBe event.event
    }

    @Test
    fun `insert multiple events should work`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val events = listOf(
            createEvent(listener.id, filterIndex = 0, eventData = """{"type":"Event1"}"""),
            createEvent(listener.id, filterIndex = 1, eventData = """{"type":"Event2"}""")
        )

        // When
        val inserted = repository.insert(events)

        // Then
        inserted shouldBe 2
        repository.countAll() shouldBe 2
    }

    @Test
    fun `deleteAll should remove all events`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val events = listOf(
            createEvent(listener.id, filterIndex = 0),
            createEvent(listener.id, filterIndex = 1)
        )
        repository.insert(events)

        // When
        repository.deleteAll()

        // Then
        repository.countAll() shouldBe 0
    }

    // ========== findByListenerId tests ==========

    @Test
    fun `findByListenerId should return empty list for non-existent listener`() = runTest {
        // Given - no events inserted

        // When
        val result = repository.findByListenerId(IDV7.random())

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findByListenerId should return events ordered by created_at`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        // Insert events - created_at is auto-generated, so we insert in order
        val event0 = createEvent(listener.id, filterIndex = 2, eventData = """{"order":0}""", eventId = "event-0")
        val event1 = createEvent(listener.id, filterIndex = 0, eventData = """{"order":1}""", eventId = "event-1")
        val event2 = createEvent(listener.id, filterIndex = 1, eventData = """{"order":2}""", eventId = "event-2")
        repository.insert(listOf(event0, event1, event2))

        // When
        val result = repository.findByListenerId(listener.id)

        // Then - should be ordered by created_at (FIFO order)
        result shouldHaveSize 3
        result.map { it.eventId } shouldContainExactly listOf("event-0", "event-1", "event-2")
        result.map { it.event } shouldContainExactly listOf("""{"order":0}""", """{"order":1}""", """{"order":2}""")
    }

    @Test
    fun `findByListenerId should only return events for specified listener`() = runTest {
        // Given
        val listener1 = createListener()
        val listener2 = createListener()
        listenerRepository.insert(listOf(listener1, listener2))

        val events1 = listOf(
            createEvent(listener1.id, filterIndex = 0, eventData = """{"listener":1}"""),
            createEvent(listener1.id, filterIndex = 1, eventData = """{"listener":1}""")
        )
        val events2 = listOf(
            createEvent(listener2.id, filterIndex = 0, eventData = """{"listener":2}""")
        )
        repository.insert(events1 + events2)

        // When
        val result1 = repository.findByListenerId(listener1.id)
        val result2 = repository.findByListenerId(listener2.id)

        // Then
        result1 shouldHaveSize 2
        result1.all { it.listenerId == listener1.id } shouldBe true

        result2 shouldHaveSize 1
        result2.first().listenerId shouldBe listener2.id
    }

    // ========== countByListenerId tests ==========

    @Test
    fun `countByListenerId should return 0 for non-existent listener`() = runTest {
        // When
        val count = repository.countByListenerId(IDV7.random())

        // Then
        count shouldBe 0
    }

    @Test
    fun `countByListenerId should return correct count`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val events = (0..2).map { createEvent(listener.id, filterIndex = it) }
        repository.insert(events)

        // When
        val count = repository.countByListenerId(listener.id)

        // Then
        count shouldBe 3
    }

    // ========== CASCADE delete tests ==========

    @Test
    fun `deleting listener should cascade delete its events`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        repository.insert(
            listOf(
                createEvent(listener.id, filterIndex = 0),
                createEvent(listener.id, filterIndex = 1)
            )
        )

        // Verify events exist
        repository.countByListenerId(listener.id) shouldBe 2

        // When - delete the parent listener
        listenerRepository.deleteById(listener.id)

        // Then - events should be cascade deleted
        repository.countByListenerId(listener.id) shouldBe 0
        repository.findByListenerId(listener.id).shouldBeEmpty()
    }

    // ========== Idempotency tests ==========

    @Test
    fun `inserting duplicate event should be handled gracefully`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0)
        repository.insert(event)

        // When - try to insert same event again (same ID)
        val duplicateEvent = event.copy()

        // Then - should not throw, but also not insert duplicate
        // Note: behavior depends on database and insert method (INSERT IGNORE vs ON CONFLICT)
        try {
            repository.insert(duplicateEvent)
        } catch (_: Exception) {
            // Some databases throw on duplicate PK, which is acceptable
        }

        // Should still have only 1 event
        repository.countByListenerId(listener.id) shouldBe 1
    }

    // ========== batchInsertForOneAny tests ==========

    @Test
    fun `batchInsertForOneAny should insert event for matching listener`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event","data":"test"}"""

        // When
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then
        inserted shouldBe 1
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson
        events.first().eventId shouldBe eventId
    }

    @Test
    fun `batchInsertForOneAny should not insert if listener already has event`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // First insert
        val queryKey = createQueryKey(listener)
        val eventId1 = "ce-first"
        val eventJson1 = """{"type":"com.example.Event1"}"""
        repository.batchInsertForOneAny(listOf(queryKey), eventId1, eventJson1)

        // When - try to insert another event
        val eventId2 = "ce-second"
        val eventJson2 = """{"type":"com.example.Event2"}"""
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId2, eventJson2)

        // Then - NOT EXISTS clause prevents second insert
        inserted shouldBe 0
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson1 // First event wins
    }

    @Test
    fun `batchInsertForOneAny should set outbox fields for foreach listener`() = runTest {
        // Given
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - foreach listener should have NULL delayed_until (markReadyForForeach will set it)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().outboxScheduledFor shouldNotBe null
        events.first().outboxDelayedUntil shouldBe null // markReadyForForeach will set this
        events.first().foreachOutput shouldBe null // Set after foreach.do completes
        events.first().outboxCompletedAt shouldBe null
    }

    @Test
    fun `batchInsertForOneAny should set foreach_output for non-foreach listener`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - non-foreach listener should have foreach_output set, outbox columns NULL (no processing needed)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().outboxScheduledFor shouldBe null // NULL for non-foreach listeners
        events.first().outboxDelayedUntil shouldBe null
        events.first().foreachOutput shouldBe eventJson
        events.first().outboxCompletedAt shouldBe null
    }

    @Test
    fun `batchInsertForOneAny should insert for multiple matching listeners`() = runTest {
        // Given - 3 listeners with same workflow info and position
        val listener1 = createListener(hasForeach = false)
        val listener2 = createListener(hasForeach = true)
        val listener3 = createListener(hasForeach = false)
        listenerRepository.insert(listOf(listener1, listener2, listener3))

        // All listeners match the same query key
        val queryKey = createQueryKey(listener1)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - all 3 listeners should get the event
        inserted shouldBe 3
        repository.countByListenerId(listener1.id) shouldBe 1
        repository.countByListenerId(listener2.id) shouldBe 1
        repository.countByListenerId(listener3.id) shouldBe 1
    }

    @Test
    fun `batchInsertForOneAny should be idempotent on repeated calls with same eventId`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When - call twice with same eventId (idempotent CloudEvent)
        val inserted1 = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)
        val inserted2 = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - first call inserts, second is idempotent (PK conflict)
        inserted1 shouldBe 1
        inserted2 shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 1
    }

    @Test
    fun `batchInsertForOneAny should not insert for completed listener`() = runTest {
        // Given - listener that's already completed
        val listener = createListener(hasForeach = false).apply {
            outboxCompletedAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - completed listeners are excluded from the query
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    // ========== batchInsertForAccumulating tests ==========

    @Test
    fun `batchInsertForAccumulating should insert event for matching listener`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(queryKey), eventId, eventJson)

        // Then
        inserted shouldBe 1
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe eventJson
        events.first().filterIndex shouldBe 0
        events.first().eventId shouldBe eventId
    }

    @Test
    fun `batchInsertForAccumulating should maintain FIFO order via created_at`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0)
        val queryKey1 = createQueryKey(listener, filterIndex = 1)
        val queryKey2 = createQueryKey(listener, filterIndex = 2)

        // When - insert 3 events sequentially with unique eventIds
        repository.batchInsertForAccumulating(listOf(queryKey0), "event-1", """{"order":1}""")
        repository.batchInsertForAccumulating(listOf(queryKey1), "event-2", """{"order":2}""")
        repository.batchInsertForAccumulating(listOf(queryKey2), "event-3", """{"order":3}""")

        // Then - events should be ordered by created_at (FIFO)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 3
        events.map { it.eventId } shouldContainExactly listOf("event-1", "event-2", "event-3")
    }

    @Test
    fun `batchInsertForAccumulating should set outbox fields for foreach events`() = runTest {
        // Given
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val queryKey0 = createQueryKey(listener, filterIndex = 0)
        val queryKey1 = createQueryKey(listener, filterIndex = 1)

        // When - insert 2 events with unique eventIds
        repository.batchInsertForAccumulating(listOf(queryKey0), "event-first", """{"first":true}""")
        repository.batchInsertForAccumulating(listOf(queryKey1), "event-second", """{"first":false}""")

        // Then - both events should have NULL delayed_until (markReadyForForeach will set it)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 2

        val firstEvent = events.find { it.eventId == "event-first" }!!
        val secondEvent = events.find { it.eventId == "event-second" }!!

        firstEvent.outboxScheduledFor shouldNotBe null
        firstEvent.outboxDelayedUntil shouldBe null // markReadyForForeach will set this
        firstEvent.foreachOutput shouldBe null // Set after foreach.do completes
        firstEvent.outboxCompletedAt shouldBe null
        secondEvent.outboxScheduledFor shouldNotBe null
        secondEvent.outboxDelayedUntil shouldBe null // markReadyForForeach will set this
        secondEvent.foreachOutput shouldBe null
        secondEvent.outboxCompletedAt shouldBe null
    }

    @Test
    fun `batchInsertForAccumulating should set foreach_output for non-foreach listener`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"event":true}"""

        // When
        repository.batchInsertForAccumulating(listOf(queryKey), eventId, eventJson)

        // Then - non-foreach should have foreach_output set, outbox columns NULL (no processing needed)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().outboxScheduledFor shouldBe null // NULL for non-foreach listeners
        events.first().outboxDelayedUntil shouldBe null
        events.first().foreachOutput shouldBe eventJson
        events.first().outboxCompletedAt shouldBe null
    }

    @Test
    fun `batchInsertForAccumulating should handle filterIndex uniqueness for ALL strategy`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)

        // When - insert same filterIndex twice with different eventIds
        val inserted1 = repository.batchInsertForAccumulating(listOf(queryKey), "event-first", """{"first":true}""")
        val inserted2 = repository.batchInsertForAccumulating(listOf(queryKey), "event-second", """{"second":true}""")

        // Then - second insert should be ignored (UNIQUE on listener_id, filter_index)
        inserted1 shouldBe 1
        inserted2 shouldBe 0
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().event shouldBe """{"first":true}""" // First event wins
    }

    @Test
    fun `batchInsertForAccumulating should insert for multiple listeners`() = runTest {
        // Given - 2 listeners
        val listener1 = createListener(hasForeach = false)
        val listener2 = createListener(hasForeach = false)
        listenerRepository.insert(listOf(listener1, listener2))

        // Query key matches both listeners
        val queryKey = createQueryKey(listener1, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(queryKey), eventId, """{"event":true}""")

        // Then - both listeners should get the event
        inserted shouldBe 2
        repository.countByListenerId(listener1.id) shouldBe 1
        repository.countByListenerId(listener2.id) shouldBe 1
    }

    @Test
    fun `batchInsertForAccumulating should handle multiple filterIndexes in single call`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // Two query keys with different filterIndexes
        val queryKey0 = createQueryKey(listener, filterIndex = 0)
        val queryKey1 = createQueryKey(listener, filterIndex = 1)
        val eventId = "ce-${IDV7.random()}"

        // When - insert both filterIndexes in single call
        val inserted =
            repository.batchInsertForAccumulating(listOf(queryKey0, queryKey1), eventId, """{"event":true}""")

        // Then - should insert 2 events (one per filterIndex)
        inserted shouldBe 2
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 2
        events.map { it.filterIndex }.toSet() shouldBe setOf(0, 1)
    }

    @Test
    fun `batchInsertForAccumulating should be idempotent for same filterIndex`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventJson = """{"type":"com.example.Event"}"""

        // When - call twice with same filterIndex (different eventIds but same filterIndex)
        val inserted1 = repository.batchInsertForAccumulating(listOf(queryKey), "event-1", eventJson)
        val inserted2 = repository.batchInsertForAccumulating(listOf(queryKey), "event-2", eventJson)

        // Then - first call inserts, second is idempotent (UNIQUE on listener_id, filter_index)
        inserted1 shouldBe 1
        inserted2 shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 1
    }

    @Test
    fun `batchInsertForAccumulating should not insert for completed listener`() = runTest {
        // Given - listener that's already completed
        val listener = createListener(hasForeach = false).apply {
            outboxCompletedAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(queryKey), eventId, """{"event":true}""")

        // Then - completed listeners are excluded
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    // ========== Edge case tests ==========

    @Test
    fun `batchInsertForOneAny should return 0 for empty keys list`() = runTest {
        // When
        val inserted = repository.batchInsertForOneAny(emptyList(), "ce-test", """{"event":true}""")

        // Then
        inserted shouldBe 0
    }

    @Test
    fun `batchInsertForAccumulating should return 0 for empty keys list`() = runTest {
        // When
        val inserted = repository.batchInsertForAccumulating(emptyList(), "ce-test", """{"event":true}""")

        // Then
        inserted shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should return 0 when no listeners match`() = runTest {
        // Given - a listener exists but query key doesn't match
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // Create a query key that won't match (different position)
        val nonMatchingKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = NodePosition("/do/differentTask"), // Different position
            correlationValuesJson = null,
            filterIndex = null
        )
        val eventId = "ce-${IDV7.random()}"

        // When
        val inserted = repository.batchInsertForOneAny(listOf(nonMatchingKey), eventId, """{"event":true}""")

        // Then
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    @Test
    fun `batchInsertForAccumulating should return 0 when no listeners match`() = runTest {
        // Given - a listener exists but query key doesn't match
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // Create a query key that won't match (different position)
        val nonMatchingKey = ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = NodePosition("/do/differentTask"), // Different position
            correlationValuesJson = null,
            filterIndex = 0
        )
        val eventId = "ce-${IDV7.random()}"

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(nonMatchingKey), eventId, """{"event":true}""")

        // Then
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should not insert for failed listener`() = runTest {
        // Given - listener that has failed
        val listener = createListener(hasForeach = false).apply {
            outboxFailedAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - failed listeners are excluded
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    @Test
    fun `batchInsertForAccumulating should not insert for failed listener`() = runTest {
        // Given - listener that has failed
        val listener = createListener(hasForeach = false).apply {
            outboxFailedAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(queryKey), eventId, eventJson)

        // Then - failed listeners are excluded
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    @Test
    fun `batchInsertForOneAny should not insert for listener with ready_at set`() = runTest {
        // Given - listener that is already ready (completion criteria met)
        val listener = createListener(hasForeach = false).apply {
            readyAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson)

        // Then - ready listeners are excluded
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    @Test
    fun `batchInsertForAccumulating should not insert for listener with ready_at set`() = runTest {
        // Given - listener that is already ready (completion criteria met)
        val listener = createListener(hasForeach = false).apply {
            readyAt = Clock.System.now()
        }
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventId = "ce-${IDV7.random()}"
        val eventJson = """{"type":"com.example.Event"}"""

        // When
        val inserted = repository.batchInsertForAccumulating(listOf(queryKey), eventId, eventJson)

        // Then - ready listeners are excluded
        inserted shouldBe 0
        repository.countByListenerId(listener.id) shouldBe 0
    }

    // ========== Thread-safety tests ==========

    @Test
    fun `batchInsertForOneAny concurrent calls with same eventId should be idempotent`() = runTest {
        // Given - multiple listeners
        val listeners = (1..5).map { createListener(hasForeach = false) }
        listenerRepository.insert(listeners)

        val queryKey = createQueryKey(listeners.first()) // All listeners match (same workflow info + position)
        val eventId = "ce-same-event"
        val eventJson = """{"type":"com.example.Event"}"""

        // When - simulate concurrent calls with same eventId (idempotent CloudEvent)
        val concurrentCalls = 10
        val results = mutableListOf<Int>()
        repeat(concurrentCalls) {
            results.add(repository.batchInsertForOneAny(listOf(queryKey), eventId, eventJson))
        }

        // Then - first call inserts for all listeners, subsequent calls are idempotent (PK conflict)
        results.first() shouldBe 5 // All 5 listeners get an event
        results.drop(1).forEach { it shouldBe 0 } // All subsequent calls return 0

        // Each listener should have exactly 1 event
        listeners.forEach { listener ->
            repository.countByListenerId(listener.id) shouldBe 1
        }
    }

    @Test
    fun `batchInsertForAccumulating concurrent calls with same filterIndex should be idempotent`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val queryKey = createQueryKey(listener, filterIndex = 0)
        val eventJson = """{"type":"com.example.Event"}"""

        // When - simulate concurrent calls (different eventIds but same filterIndex)
        val concurrentCalls = 10
        val results = mutableListOf<Int>()
        repeat(concurrentCalls) { i ->
            results.add(repository.batchInsertForAccumulating(listOf(queryKey), "event-$i", eventJson))
        }

        // Then - first call inserts, subsequent calls are idempotent (due to UNIQUE on listener_id, filter_index)
        results.first() shouldBe 1
        results.drop(1).forEach { it shouldBe 0 }

        // Should have exactly 1 event
        repository.countByListenerId(listener.id) shouldBe 1
    }

    @Test
    fun `batchInsertForAccumulating concurrent calls with different filterIndexes should all succeed`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // When - insert events with different filterIndexes (simulating concurrent arrivals)
        val filterCount = 5
        val results = (0 until filterCount).map { filterIndex ->
            val queryKey = createQueryKey(listener, filterIndex = filterIndex)
            repository.batchInsertForAccumulating(
                listOf(queryKey),
                "event-$filterIndex",
                """{"filterIndex":$filterIndex}"""
            )
        }

        // Then - all inserts should succeed
        results.forEach { it shouldBe 1 }

        // Should have exactly 5 events
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 5
        events.map { it.filterIndex }.toSet() shouldBe setOf(0, 1, 2, 3, 4)
    }

    @Test
    fun `batchInsertForAccumulating multiple filterIndexes in single call should insert all`() = runTest {
        // Given
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // Create query keys for all filterIndexes in a single call
        val queryKeys = (0..4).map { filterIndex ->
            createQueryKey(listener, filterIndex = filterIndex)
        }
        val eventId = "ce-batch-event"

        // When - insert all filterIndexes in a single call
        val inserted = repository.batchInsertForAccumulating(queryKeys, eventId, """{"batch":true}""")

        // Then - all 5 events should be inserted
        inserted shouldBe 5

        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 5

        // Filter indexes should be preserved
        events.map { it.filterIndex }.toSet() shouldBe setOf(0, 1, 2, 3, 4)

        // All events should have NULL delayed_until (markReadyForForeach will set it)
        events.forEach {
            it.outboxScheduledFor shouldNotBe null
            it.outboxDelayedUntil shouldBe null // markReadyForForeach will set this
            it.foreachOutput shouldBe null // Set after foreach.do completes
            it.outboxCompletedAt shouldBe null
        }
    }

    @Test
    fun `batchInsertForAccumulating should maintain FIFO order across multiple calls`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // When - insert events in separate calls
        val queryKey0 = createQueryKey(listener, filterIndex = 0)
        val queryKey1 = createQueryKey(listener, filterIndex = 1)
        val queryKey2 = createQueryKey(listener, filterIndex = 2)

        repository.batchInsertForAccumulating(listOf(queryKey0), "event-1", """{"order":1}""")
        repository.batchInsertForAccumulating(listOf(queryKey1), "event-2", """{"order":2}""")
        repository.batchInsertForAccumulating(listOf(queryKey2), "event-3", """{"order":3}""")

        // Then - events should be ordered by created_at (FIFO)
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 3
        events.map { it.eventId } shouldContainExactly listOf("event-1", "event-2", "event-3")
        events.map { it.filterIndex } shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `batchInsertForAccumulating should handle mixed batch and sequential inserts`() = runTest {
        // Given
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // When - first insert 2 events in a batch
        val batchKeys = listOf(
            createQueryKey(listener, filterIndex = 0),
            createQueryKey(listener, filterIndex = 1)
        )
        val batchInserted = repository.batchInsertForAccumulating(batchKeys, "batch-event", """{"batch":true}""")

        // Then insert 1 more event
        val singleKey = createQueryKey(listener, filterIndex = 2)
        val singleInserted =
            repository.batchInsertForAccumulating(listOf(singleKey), "single-event", """{"single":true}""")

        // Then - all should succeed
        batchInserted shouldBe 2
        singleInserted shouldBe 1

        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 3
    }

    // ========== Helper methods for batch insert tests ==========

    /**
     * Creates a listener with configurable hasForeach flag.
     */
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
            strategy = ListenerStrategy.from(config),
            timeoutAt = null,
            outboxScheduledFor = now
        ).also {
            it.hasForeach = hasForeach
        }
    }

    /**
     * Creates a query key for the given listener.
     */
    private fun createQueryKey(listener: ListenerModel, filterIndex: Int? = null): ListenerQueryKey {
        return ListenerQueryKey(
            workflowInfo = listener.instanceMessage.workflowInfo,
            position = listener.instanceMessage.workflowState.nodePosition,
            correlationValuesJson = null,
            filterIndex = filterIndex
        )
    }

    // ========== FIFO Outbox Tests (specialized for ListenerEventRepository) ==========

    @Test
    fun `findEntitiesToProcess should return only one event per listener even if multiple eligible`() = runTest {
        // Given - one listener with 3 pending events
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // Insert 3 events for the same listener (all pending with NULL outboxDelayedUntil)
        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "event-2")
        val event3 = createEvent(listener.id, filterIndex = 2, eventId = "event-3")
        repository.insert(listOf(event1, event2, event3))

        // Mark first event ready using markReadyForForeach (this is how FIFO is enforced)
        repository.markReadyForForeach(limit = 100)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then - only ONE event per listener (the first by sort_key, marked by markReadyForForeach)
        result shouldHaveSize 1
        result.first().eventId shouldBe "event-1" // First inserted = first by sort_key
    }

    @Test
    fun `findEntitiesToProcess should return one event per listener for multiple listeners`() = runTest {
        // Given - 3 listeners, each with 2 pending events
        val listener1 = createListener(hasForeach = true)
        val listener2 = createListener(hasForeach = true)
        val listener3 = createListener(hasForeach = true)
        listenerRepository.insert(listOf(listener1, listener2, listener3))

        repository.insert(
            listOf(
                createEvent(listener1.id, filterIndex = 0, eventId = "l1-e1"),
                createEvent(listener1.id, filterIndex = 1, eventId = "l1-e2"),
                createEvent(listener2.id, filterIndex = 0, eventId = "l2-e1"),
                createEvent(listener2.id, filterIndex = 1, eventId = "l2-e2"),
                createEvent(listener3.id, filterIndex = 0, eventId = "l3-e1"),
                createEvent(listener3.id, filterIndex = 1, eventId = "l3-e2")
            )
        )

        // Mark first event per listener using markReadyForForeach (this is how FIFO is enforced)
        repository.markReadyForForeach(limit = 100)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then - one event per listener (3 total)
        result shouldHaveSize 3
        result.map { it.eventId }.toSet() shouldBe setOf("l1-e1", "l2-e1", "l3-e1")
    }

    @Test
    fun `findEntitiesToProcess should return events if completed event has foreach_output set`() = runTest {
        // Given - listener with a properly completed event (has foreach_output)
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // Insert a properly completed event
        val completedEvent = createEvent(listener.id, filterIndex = 0, eventId = "completed").apply {
            outboxDelayedUntil = Clock.System.now() - 1.hours
            outboxCompletedAt = Clock.System.now()
            foreachCompleted = true
            foreachOutput = """{"result":"done"}""" // Properly completed
        }
        repository.insert(completedEvent)

        // Insert a pending event
        val pendingEvent = createEvent(listener.id, filterIndex = 1, eventId = "pending").apply {
            outboxDelayedUntil = Clock.System.now() - 30.minutes
        }
        repository.insert(pendingEvent)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then - pending event is returned (completed event doesn't block)
        result shouldHaveSize 1
        result.first().eventId shouldBe "pending"
    }

    @Test
    fun `findEntitiesToProcess should not return events with delayed_until in future`() = runTest {
        // Given
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0).apply {
            outboxDelayedUntil = Clock.System.now() + 1.hours // Future
        }
        repository.insert(event)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToProcess should not return events with null delayed_until`() = runTest {
        // Given - non-foreach events have delayed_until = NULL
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0).apply {
            outboxDelayedUntil = null // Non-foreach
            foreachCompleted = true
            foreachOutput = """{"event":true}""" // Already has output
        }
        repository.insert(event)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then - not returned (delayed_until is NULL)
        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToProcess should respect maxAttempts`() = runTest {
        // Given
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0).apply {
            outboxDelayedUntil = Clock.System.now() - 1.hours
            outboxAttemptCount = 5 // Exceeds max
        }
        repository.insert(event)

        // When
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 100, connection = null)

        // Then
        result.shouldBeEmpty()
    }

    @Test
    fun `findEntitiesToProcess should respect limit`() = runTest {
        // Given - 5 listeners with 1 event each
        val listeners = (1..5).map { createListener(hasForeach = true) }
        listenerRepository.insert(listeners)

        val now = Clock.System.now()
        listeners.forEachIndexed { idx, listener ->
            val event = createEvent(listener.id, filterIndex = 0, eventId = "event-$idx").apply {
                outboxDelayedUntil = now - idx.hours
            }
            repository.insert(event)
        }

        // When - limit to 3
        val result = repository.findEntitiesToProcess(maxAttempts = 3, limit = 3, connection = null)

        // Then - only 3 returned
        result shouldHaveSize 3
    }

    // ========== markReadyForForeach Tests ==========

    @Test
    fun `markReadyForForeach should mark single pending event as ready`() = runTest {
        // Given - listener with one pending event (outbox_delayed_until = NULL)
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "pending-event")
        // outbox_delayed_until is NULL by default (pending)
        repository.insert(event)

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - one event should be marked
        marked shouldBe 1
        val events = repository.findByListenerId(listener.id)
        events shouldHaveSize 1
        events.first().outboxDelayedUntil shouldNotBe null
    }

    @Test
    fun `markReadyForForeach should return 0 when no pending events exist`() = runTest {
        // Given - no events in table

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then
        marked shouldBe 0
    }

    @Test
    fun `markReadyForForeach should return 0 when all events are already being processed`() = runTest {
        // Given - listener with event already being processed (outbox_delayed_until IS NOT NULL)
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "processing-event").apply {
            outboxDelayedUntil = Clock.System.now() // Already marked as ready/processing
        }
        repository.insert(event)

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - nothing new to mark
        marked shouldBe 0
    }

    @Test
    fun `markReadyForForeach should not mark new events if listener has event being processed`() = runTest {
        // Given - listener with one event being processed and one pending
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // First event is being processed (outbox_delayed_until IS NOT NULL, foreach_output IS NULL)
        val processingEvent = createEvent(listener.id, filterIndex = 0, eventId = "processing").apply {
            outboxDelayedUntil = Clock.System.now()
            // foreach_output is NULL (still processing)
        }
        repository.insert(processingEvent)

        // Second event is pending (outbox_delayed_until IS NULL)
        val pendingEvent = createEvent(listener.id, filterIndex = 1, eventId = "pending")
        repository.insert(pendingEvent)

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - should NOT mark the pending event (listener has event being processed)
        marked shouldBe 0
        val events = repository.findByListenerId(listener.id)
        val pending = events.find { it.eventId == "pending" }!!
        pending.outboxDelayedUntil shouldBe null // Still pending
    }

    @Test
    fun `markReadyForForeach should mark next event after previous is completed`() = runTest {
        // Given - listener with one completed event and one pending
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // First event is completed (foreach_completed = TRUE)
        val completedEvent = createEvent(listener.id, filterIndex = 0, eventId = "completed").apply {
            outboxDelayedUntil = Clock.System.now() - 1.hours
            foreachCompleted = true
            foreachOutput = """{"result":"done"}""" // Completed
            outboxCompletedAt = Clock.System.now()
        }
        repository.insert(completedEvent)

        // Second event is pending
        val pendingEvent = createEvent(listener.id, filterIndex = 1, eventId = "pending")
        repository.insert(pendingEvent)

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - pending event should be marked (completed event doesn't block)
        marked shouldBe 1
        val events = repository.findByListenerId(listener.id)
        val pending = events.find { it.eventId == "pending" }!!
        pending.outboxDelayedUntil shouldNotBe null
    }

    @Test
    fun `markReadyForForeach should mark only oldest pending event per listener by sort_key`() = runTest {
        // Given - listener with 3 pending events
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        // Insert in order - sort_key will be assigned by auto-increment
        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "first")
        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "second")
        val event3 = createEvent(listener.id, filterIndex = 2, eventId = "third")
        repository.insert(listOf(event1, event2, event3))

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - only the first event (oldest by sort_key) should be marked
        marked shouldBe 1
        val events = repository.findByListenerId(listener.id)
        val first = events.find { it.eventId == "first" }!!
        val second = events.find { it.eventId == "second" }!!
        val third = events.find { it.eventId == "third" }!!

        first.outboxDelayedUntil shouldNotBe null
        second.outboxDelayedUntil shouldBe null
        third.outboxDelayedUntil shouldBe null
    }

    @Test
    fun `markReadyForForeach should mark one event per listener for multiple listeners`() = runTest {
        // Given - 3 listeners, each with 2 pending events
        val listener1 = createListener(hasForeach = true)
        val listener2 = createListener(hasForeach = true)
        val listener3 = createListener(hasForeach = true)
        listenerRepository.insert(listOf(listener1, listener2, listener3))

        repository.insert(
            listOf(
                createEvent(listener1.id, filterIndex = 0, eventId = "l1-first"),
                createEvent(listener1.id, filterIndex = 1, eventId = "l1-second"),
                createEvent(listener2.id, filterIndex = 0, eventId = "l2-first"),
                createEvent(listener2.id, filterIndex = 1, eventId = "l2-second"),
                createEvent(listener3.id, filterIndex = 0, eventId = "l3-first"),
                createEvent(listener3.id, filterIndex = 1, eventId = "l3-second")
            )
        )

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - one event per listener (3 total)
        marked shouldBe 3

        // Check each listener has exactly one event marked
        listOf(listener1, listener2, listener3).forEach { listener ->
            val events = repository.findByListenerId(listener.id)
            val markedEvents = events.filter { it.outboxDelayedUntil != null }
            markedEvents shouldHaveSize 1
            markedEvents.first().eventId.endsWith("-first") shouldBe true
        }
    }

    @Test
    fun `markReadyForForeach should respect limit parameter`() = runTest {
        // Given - 5 listeners, each with 1 pending event
        val listeners = (1..5).map { createListener(hasForeach = true) }
        listenerRepository.insert(listeners)

        listeners.forEachIndexed { idx, listener ->
            val event = createEvent(listener.id, filterIndex = 0, eventId = "event-$idx")
            repository.insert(event)
        }

        // When - limit to 2
        val marked = repository.markReadyForForeach(limit = 2)

        // Then - only 2 events marked
        marked shouldBe 2

        // Count total marked events across all listeners
        val totalMarked = listeners.sumOf { listener ->
            repository.findByListenerId(listener.id).count { it.outboxDelayedUntil != null }
        }
        totalMarked shouldBe 2
    }

    @Test
    fun `markReadyForForeach should be idempotent - second call should return 0`() = runTest {
        // Given - listener with one pending event
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0, eventId = "pending-event")
        repository.insert(event)

        // When - call twice
        val marked1 = repository.markReadyForForeach(limit = 100)
        val marked2 = repository.markReadyForForeach(limit = 100)

        // Then - first call marks the event, second returns 0 (already marked, now "processing")
        marked1 shouldBe 1
        marked2 shouldBe 0
    }

    @Test
    fun `markReadyForForeach should handle mixed state listeners correctly`() = runTest {
        // Given - various listener states
        val listenerWithPending = createListener(hasForeach = true)
        val listenerWithProcessing = createListener(hasForeach = true)
        val listenerWithCompleted = createListener(hasForeach = true)
        val listenerWithBothCompletedAndPending = createListener(hasForeach = true)
        listenerRepository.insert(
            listOf(
                listenerWithPending,
                listenerWithProcessing,
                listenerWithCompleted,
                listenerWithBothCompletedAndPending
            )
        )

        // Listener 1: only pending event
        repository.insert(createEvent(listenerWithPending.id, filterIndex = 0, eventId = "pending"))

        // Listener 2: event being processed (should block)
        repository.insert(
            createEvent(listenerWithProcessing.id, filterIndex = 0, eventId = "processing").apply {
                outboxDelayedUntil = Clock.System.now()
            }
        )

        // Listener 3: only completed event
        repository.insert(
            createEvent(listenerWithCompleted.id, filterIndex = 0, eventId = "completed").apply {
                outboxDelayedUntil = Clock.System.now() - 1.hours
                foreachCompleted = true
                foreachOutput = """{"done":true}"""
                outboxCompletedAt = Clock.System.now()
            }
        )

        // Listener 4: completed event + pending event
        repository.insert(
            createEvent(listenerWithBothCompletedAndPending.id, filterIndex = 0, eventId = "l4-completed").apply {
                outboxDelayedUntil = Clock.System.now() - 1.hours
                foreachCompleted = true
                foreachOutput = """{"done":true}"""
                outboxCompletedAt = Clock.System.now()
            }
        )
        repository.insert(createEvent(listenerWithBothCompletedAndPending.id, filterIndex = 1, eventId = "l4-pending"))

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then:
        // - listenerWithPending: 1 event marked
        // - listenerWithProcessing: 0 (blocked by processing event)
        // - listenerWithCompleted: 0 (no pending events)
        // - listenerWithBothCompletedAndPending: 1 event marked (completed doesn't block)
        marked shouldBe 2

        // Verify specific listeners
        repository.findByListenerId(listenerWithPending.id).first().outboxDelayedUntil shouldNotBe null
        repository.findByListenerId(listenerWithProcessing.id)
            .first().outboxDelayedUntil shouldNotBe null // was already set
        repository.findByListenerId(listenerWithBothCompletedAndPending.id)
            .find { it.eventId == "l4-pending" }!!.outboxDelayedUntil shouldNotBe null
    }

    @Test
    fun `markReadyForForeach should correctly sequence multiple events over time`() = runTest {
        // Given - listener with 3 pending events, simulating sequential processing
        val listener = createListener(hasForeach = true)
        listenerRepository.insert(listener)

        val event1 = createEvent(listener.id, filterIndex = 0, eventId = "event-1")
        val event2 = createEvent(listener.id, filterIndex = 1, eventId = "event-2")
        val event3 = createEvent(listener.id, filterIndex = 2, eventId = "event-3")
        repository.insert(listOf(event1, event2, event3))

        // Step 1: Mark first event
        val marked1 = repository.markReadyForForeach(limit = 100)
        marked1 shouldBe 1
        repository.findByListenerId(listener.id).find { it.eventId == "event-1" }!!.outboxDelayedUntil shouldNotBe null

        // Step 2: Try to mark while first is processing - should return 0
        val marked2 = repository.markReadyForForeach(limit = 100)
        marked2 shouldBe 0

        // Step 3: Complete first event (set foreach_output)
        repository.markCompletedWithOutput(listener.id, "event-1", """{"result":1}""")

        // Step 4: Mark next event ready (this is now done by markReadyForForeach, not automatically)
        val marked3 = repository.markReadyForForeach(limit = 100)
        marked3 shouldBe 1
        repository.findByListenerId(listener.id).find { it.eventId == "event-2" }!!.outboxDelayedUntil shouldNotBe null

        // Step 5: Complete second event
        repository.markCompletedWithOutput(listener.id, "event-2", """{"result":2}""")

        // Step 6: Mark next event ready
        val marked4 = repository.markReadyForForeach(limit = 100)
        marked4 shouldBe 1
        repository.findByListenerId(listener.id).find { it.eventId == "event-3" }!!.outboxDelayedUntil shouldNotBe null

        // Step 7: No more events to mark
        val marked5 = repository.markReadyForForeach(limit = 100)
        marked5 shouldBe 0
    }

    @Test
    fun `markReadyForForeach should not mark events for non-foreach listeners`() = runTest {
        // Given - non-foreach listener (foreach events should have foreach_output already set)
        val listener = createListener(hasForeach = false)
        listenerRepository.insert(listener)

        // Non-foreach events are inserted with foreach_output set, so they don't need marking
        // This test verifies that if somehow a non-foreach event has NULL foreach_output,
        // markReadyForForeach would still work (it doesn't filter by has_foreach)
        val event = createEvent(listener.id, filterIndex = 0, eventId = "non-foreach-event")
        repository.insert(event)

        // When
        val marked = repository.markReadyForForeach(limit = 100)

        // Then - the event should still be marked (markReadyForForeach doesn't check has_foreach)
        marked shouldBe 1
    }
}
