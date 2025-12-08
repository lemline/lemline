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
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.repositories.bases.ops.IdRepositoryTest
import com.lemline.runner.repositories.bases.ops.OutboxRepositoryTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

    // Shared listener for IdTests - needs to be created before tests run
    private var sharedListenerForIdTests: ListenerModel? = null

    /**
     * Creates entity for IdTests. Creates a listener if needed and returns an event for that listener.
     */
    private fun createEntityForIdTests(): ListenerEventModel {
        val listener = sharedListenerForIdTests ?: createListener().also {
            sharedListenerForIdTests = it
            kotlinx.coroutines.runBlocking { listenerRepository.insert(it) }
        }
        return createEvent(listener.id, filterIndex = null, cloudEventId = IDV7.random().toString())
    }

    @BeforeEach
    fun setup() = runTest {
        // Clear existing data and reset shared state
        repository.deleteAll()
        listenerRepository.deleteAll()
        sharedListenerForIdTests = null

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
     */
    private fun createEvent(
        listenerId: IDV7,
        filterIndex: Int? = null,
        eventData: String = """{"type":"com.example.Event"}""",
        cloudEventId: String? = null
    ): ListenerEventModel {
        val id = if (filterIndex != null) {
            listenerId.derive("-filter-$filterIndex")
        } else {
            IDV7.random()
        }
        return ListenerEventModel(
            id = id,
            listenerId = listenerId,
            filterIndex = filterIndex,
            cloudEventId = cloudEventId,
            event = eventData
        )
    }

    // ========== Basic CRUD tests ==========

    @Test
    fun `insert and findById should work`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        val event = createEvent(listener.id, filterIndex = 0)

        // When
        repository.insert(event)
        val found = repository.findById(event.id)

        // Then
        found shouldBe event.copy(createdAt = found?.createdAt)
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
    fun `findByListenerId should return events ordered by filter index`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        // Insert events in reverse order
        val event2 = createEvent(listener.id, filterIndex = 2, eventData = """{"idx":2}""")
        val event0 = createEvent(listener.id, filterIndex = 0, eventData = """{"idx":0}""")
        val event1 = createEvent(listener.id, filterIndex = 1, eventData = """{"idx":1}""")
        repository.insert(listOf(event2, event0, event1))

        // When
        val result = repository.findByListenerId(listener.id)

        // Then - should be ordered by filter_index
        result shouldHaveSize 3
        result.map { it.filterIndex } shouldContainExactly listOf(0, 1, 2)
        result.map { it.event } shouldContainExactly listOf("""{"idx":0}""", """{"idx":1}""", """{"idx":2}""")
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

    // ========== batchCountByListenerIds tests ==========

    @Test
    fun `batchCountByListenerIds should return empty map for empty list`() = runTest {
        // When
        val result = repository.batchCountByListenerIds(emptyList())

        // Then
        result shouldBe emptyMap()
    }

    @Test
    fun `batchCountByListenerIds should return counts for multiple listeners`() = runTest {
        // Given
        val listener1 = createListener()
        val listener2 = createListener()
        val listener3 = createListener()
        listenerRepository.insert(listOf(listener1, listener2, listener3))

        // listener1: 2 events, listener2: 1 event, listener3: 0 events
        repository.insert(
            listOf(
                createEvent(listener1.id, filterIndex = 0),
                createEvent(listener1.id, filterIndex = 1),
                createEvent(listener2.id, filterIndex = 0)
            )
        )

        // When
        val result = repository.batchCountByListenerIds(listOf(listener1.id, listener2.id, listener3.id))

        // Then - listener3 not in map because it has 0 events (GROUP BY doesn't include it)
        result shouldContainExactly mapOf(
            listener1.id to 2,
            listener2.id to 1
        )
    }

    @Test
    fun `batchCountByListenerIds should handle non-existent listeners`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)
        repository.insert(createEvent(listener.id, filterIndex = 0))

        val nonExistentId = IDV7.random()

        // When
        val result = repository.batchCountByListenerIds(listOf(listener.id, nonExistentId))

        // Then
        result shouldBe mapOf(listener.id to 1)
    }

    // ========== batchFindByListenerIds tests ==========

    @Test
    fun `batchFindByListenerIds should return empty map for empty list`() = runTest {
        // When
        val result = repository.batchFindByListenerIds(emptyList())

        // Then
        result shouldBe emptyMap()
    }

    @Test
    fun `batchFindByListenerIds should return events grouped by listener`() = runTest {
        // Given
        val listener1 = createListener()
        val listener2 = createListener()
        val listener3 = createListener()
        listenerRepository.insert(listOf(listener1, listener2, listener3))

        // listener1: 2 events, listener2: 1 event, listener3: 0 events
        repository.insert(
            listOf(
                createEvent(listener1.id, filterIndex = 0, eventData = """{"l1e0":true}"""),
                createEvent(listener1.id, filterIndex = 1, eventData = """{"l1e1":true}"""),
                createEvent(listener2.id, filterIndex = 0, eventData = """{"l2e0":true}""")
            )
        )

        // When
        val result = repository.batchFindByListenerIds(listOf(listener1.id, listener2.id, listener3.id))

        // Then
        result.keys shouldBe setOf(listener1.id, listener2.id)
        result[listener1.id]!! shouldHaveSize 2
        result[listener1.id]!!.map { it.event } shouldContainExactly listOf("""{"l1e0":true}""", """{"l1e1":true}""")
        result[listener2.id]!! shouldHaveSize 1
        result[listener2.id]!!.first().event shouldBe """{"l2e0":true}"""
    }

    @Test
    fun `batchFindByListenerIds should return events ordered by filter index`() = runTest {
        // Given
        val listener = createListener()
        listenerRepository.insert(listener)

        // Insert events in reverse order
        repository.insert(
            listOf(
                createEvent(listener.id, filterIndex = 2, eventData = """{"idx":2}"""),
                createEvent(listener.id, filterIndex = 0, eventData = """{"idx":0}"""),
                createEvent(listener.id, filterIndex = 1, eventData = """{"idx":1}""")
            )
        )

        // When
        val result = repository.batchFindByListenerIds(listOf(listener.id))

        // Then - events should be ordered by filter_index
        result[listener.id]!! shouldHaveSize 3
        result[listener.id]!!.map { it.filterIndex } shouldContainExactly listOf(0, 1, 2)
        result[listener.id]!!.map { it.event } shouldContainExactly listOf(
            """{"idx":0}""",
            """{"idx":1}""",
            """{"idx":2}"""
        )
    }

    // ========== deleteByListenerId tests ==========

    @Test
    fun `deleteByListenerId should return 0 for non-existent listener`() = runTest {
        // When
        val deleted = repository.deleteByListenerId(IDV7.random())

        // Then
        deleted shouldBe 0
    }

    @Test
    fun `deleteByListenerId should delete all events for listener`() = runTest {
        // Given
        val listener1 = createListener()
        val listener2 = createListener()
        listenerRepository.insert(listOf(listener1, listener2))

        repository.insert(
            listOf(
                createEvent(listener1.id, filterIndex = 0),
                createEvent(listener1.id, filterIndex = 1),
                createEvent(listener2.id, filterIndex = 0)
            )
        )

        // When
        val deleted = repository.deleteByListenerId(listener1.id)

        // Then
        deleted shouldBe 2
        repository.findByListenerId(listener1.id).shouldBeEmpty()
        repository.findByListenerId(listener2.id) shouldHaveSize 1
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

    // ========== Nested Standard Repository Tests ==========

    @Nested
    inner class IdTests : IdRepositoryTest<ListenerEventModel>(
        idRepository = { repository.idRepository },
        crudRepository = { repository },
        createEntity = ::createEntityForIdTests
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<ListenerEventModel>(
        outboxRepository = { repository },
        crudRepository = { repository },
        createEntity = ::createEntityForIdTests,
        getEntityKey = { it.id },
        databaseManager = { databaseManager }
    )
}
