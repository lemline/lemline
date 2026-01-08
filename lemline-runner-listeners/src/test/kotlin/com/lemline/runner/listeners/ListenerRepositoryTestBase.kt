// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Abstract base class for ListenerRepository tests.
 * Provides common test infrastructure for testing against different database types.
 */
abstract class ListenerRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): ListenerRepository
    protected abstract fun getEventRepository(): ListenerEventRepository

    private fun createEntity() = ListenerModel.random()
    private fun modifyEntity(entity: ListenerModel): ListenerModel {
        val randomInstant = Clock.System.now() + Random.nextInt(-1000, 1000).days
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<ListenerModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ListenerModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<ListenerModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ListenerModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    /**
     * Tests for listener timeout functionality.
     */
    @Nested
    inner class TimeoutTests {

        private val repository: ListenerRepository by lazy { getRepository() }

        @BeforeEach
        fun setup() = runTest {
            repository.deleteAll()
        }

        private fun createListener(timeoutAt: kotlin.time.Instant?): ListenerModel {
            return ListenerModel.random().copy(
                timeoutAt = timeoutAt,
            ).apply {
                // Reset outbox fields to pending state
                outboxCompletedAt = null
                outboxFailedAt = null
                outboxDelayedUntil = Clock.System.now()
            }
        }

        @Test
        fun `findTimedOut returns listeners past timeout`() = runTest {
            // Given: A listener with timeout in the past
            val timedOutListener = createListener(
                timeoutAt = Clock.System.now() - 1.hours
            )
            repository.insert(timedOutListener)

            // When
            val timedOut = repository.findTimedOut(10)

            // Then
            timedOut.size shouldBe 1
            timedOut[0].id shouldBe timedOutListener.id
        }

        @Test
        fun `findTimedOut excludes listeners with future timeout`() = runTest {
            // Given: A listener with timeout in the future
            val futureListener = createListener(
                timeoutAt = Clock.System.now() + 1.hours
            )
            repository.insert(futureListener)

            // When
            val timedOut = repository.findTimedOut(10)

            // Then
            timedOut.size shouldBe 0
        }

        @Test
        fun `findTimedOut excludes listeners without timeout`() = runTest {
            // Given: A listener without timeout set
            val noTimeoutListener = createListener(timeoutAt = null)
            repository.insert(noTimeoutListener)

            // When
            val timedOut = repository.findTimedOut(10)

            // Then
            timedOut.size shouldBe 0
        }

        @Test
        fun `findTimedOut excludes already completed listeners`() = runTest {
            // Given: A timed out but already completed listener
            val completedListener = createListener(
                timeoutAt = Clock.System.now() - 1.hours
            )
            repository.insert(completedListener)
            repository.markCompleted(completedListener.id)

            // When
            val timedOut = repository.findTimedOut(10)

            // Then
            timedOut.size shouldBe 0
        }

        @Test
        fun `findTimedOut excludes already failed listeners`() = runTest {
            // Given: A timed out but already failed listener
            val failedListener = createListener(
                timeoutAt = Clock.System.now() - 1.hours
            )
            repository.insert(failedListener)
            repository.markFailed(
                id = failedListener.id,
                errorClass = "TestException",
                errorMessage = "Test error",
                errorStackTrace = null
            )

            // When
            val timedOut = repository.findTimedOut(10)

            // Then
            timedOut.size shouldBe 0
        }

        @Test
        fun `markFailed sets failure fields correctly`() = runTest {
            // Given: A listener
            val listener = createListener(timeoutAt = Clock.System.now() - 1.hours)
            repository.insert(listener)

            // When
            repository.markFailed(
                id = listener.id,
                errorClass = "ListenerTimeoutException",
                errorMessage = "Listen task timed out",
                errorStackTrace = "stack trace here"
            )

            // Then
            val updated = repository.findById(listener.id)
            updated shouldNotBe null
            updated!!.outboxFailedAt shouldNotBe null
            updated.outboxErrorClass shouldBe "ListenerTimeoutException"
            updated.outboxErrorMessage shouldBe "Listen task timed out"
            updated.outboxErrorStackTrace shouldBe "stack trace here"
        }

        @Test
        fun `findTimedOut respects limit parameter`() = runTest {
            // Given: Multiple timed out listeners
            repeat(5) {
                val listener = createListener(
                    timeoutAt = Clock.System.now() - 1.hours
                )
                repository.insert(listener)
            }

            // When: Query with limit of 2
            val timedOut = repository.findTimedOut(2)

            // Then
            timedOut.size shouldBe 2
        }
    }

    /**
     * Tests for markListenerCompletedByUntilEvent functionality.
     */
    @Nested
    inner class MarkCompletedByUntilEventTests {

        private val repository: ListenerRepository by lazy { getRepository() }

        @BeforeEach
        fun setup() = runTest {
            repository.deleteAll()
        }

        private fun createListener(strategy: ListenerStrategy): ListenerModel {
            return ListenerModel.random().copy(
                listenerStrategy = strategy,
            ).apply {
                closedAt = null
                outboxCompletedAt = null
                outboxFailedAt = null
                outboxDelayedUntil = null
            }
        }

        private fun createQueryKey(listener: ListenerModel, strategy: ListenerStrategy? = null): ListenerQueryKey {
            return ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = null,
                filterIndex = null,
                listenerStrategy = strategy
            )
        }

        @Test
        fun `should mark ANY_UNTIL_EVENT listener as completed`() = runTest {
            // Given: An ANY_UNTIL_EVENT listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            repository.insert(listener)
            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EVENT)

            // When
            val count = repository.markListenerCompletedByUntilEvent(listOf(queryKey))

            // Then
            count shouldBe 1
            val updated = repository.findById(listener.id)
            updated shouldNotBe null
            updated!!.closedAt shouldNotBe null
            updated.outboxDelayedUntil shouldBe null // Should NOT set outbox_delayed_until
        }

        @Test
        fun `should return 0 for empty keys list`() = runTest {
            // Given: An ANY_UNTIL_EVENT listener exists
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            repository.insert(listener)

            // When
            val count = repository.markListenerCompletedByUntilEvent(emptyList())

            // Then
            count shouldBe 0
            val updated = repository.findById(listener.id)
            updated!!.closedAt shouldBe null
        }

        @Test
        fun `should return 0 when keys have wrong strategy`() = runTest {
            // Given: An ANY_UNTIL_EVENT listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            repository.insert(listener)

            // When: Keys have ALL strategy (not ANY_UNTIL_EVENT)
            val queryKey = createQueryKey(listener, ListenerStrategy.ALL)
            val count = repository.markListenerCompletedByUntilEvent(listOf(queryKey))

            // Then: Should filter out non-ANY_UNTIL_EVENT keys
            count shouldBe 0
            val updated = repository.findById(listener.id)
            updated!!.closedAt shouldBe null
        }

        @Test
        fun `should not mark listener with wrong strategy`() = runTest {
            // Given: Listeners with different strategies
            val oneListener = createListener(ListenerStrategy.ONE)
            val anyListener = createListener(ListenerStrategy.ANY)
            val allListener = createListener(ListenerStrategy.ALL)
            repository.insert(oneListener)
            repository.insert(anyListener)
            repository.insert(allListener)

            // When: Send keys with ANY_UNTIL_EVENT strategy
            val queryKeys = listOf(
                createQueryKey(oneListener, ListenerStrategy.ANY_UNTIL_EVENT),
                createQueryKey(anyListener, ListenerStrategy.ANY_UNTIL_EVENT),
                createQueryKey(allListener, ListenerStrategy.ANY_UNTIL_EVENT)
            )
            val count = repository.markListenerCompletedByUntilEvent(queryKeys)

            // Then: No listeners should be marked (strategy mismatch)
            count shouldBe 0
        }

        @Test
        fun `should skip already completed listeners`() = runTest {
            // Given: An already completed ANY_UNTIL_EVENT listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EVENT).apply {
                closedAt = Clock.System.now()
            }
            repository.insert(listener)
            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EVENT)

            // When
            val count = repository.markListenerCompletedByUntilEvent(listOf(queryKey))

            // Then
            count shouldBe 0
        }

        @Test
        fun `should mark multiple matching listeners`() = runTest {
            // Given: Multiple ANY_UNTIL_EVENT listeners
            val listener1 = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            val listener2 = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            repository.insert(listener1)
            repository.insert(listener2)

            val queryKeys = listOf(
                createQueryKey(listener1, ListenerStrategy.ANY_UNTIL_EVENT),
                createQueryKey(listener2, ListenerStrategy.ANY_UNTIL_EVENT)
            )

            // When
            val count = repository.markListenerCompletedByUntilEvent(queryKeys)

            // Then
            count shouldBe 2
            repository.findById(listener1.id)!!.closedAt shouldNotBe null
            repository.findById(listener2.id)!!.closedAt shouldNotBe null
        }

        @Test
        fun `should filter keys and only process ANY_UNTIL_EVENT keys`() = runTest {
            // Given: An ANY_UNTIL_EVENT listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            repository.insert(listener)

            // When: Keys include mixed strategies (only ANY_UNTIL_EVENT should be processed)
            val queryKeys = listOf(
                createQueryKey(listener, ListenerStrategy.ALL),  // Should be filtered out
                createQueryKey(listener, ListenerStrategy.ONE),  // Should be filtered out
                createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EVENT)  // Should match
            )
            val count = repository.markListenerCompletedByUntilEvent(queryKeys)

            // Then
            count shouldBe 1
            repository.findById(listener.id)!!.closedAt shouldNotBe null
        }

        @Test
        fun `should return 0 when no matching listener exists`() = runTest {
            // Given: No listeners exist
            val fakeListener = createListener(ListenerStrategy.ANY_UNTIL_EVENT)
            val queryKey = createQueryKey(fakeListener, ListenerStrategy.ANY_UNTIL_EVENT)

            // When
            val count = repository.markListenerCompletedByUntilEvent(listOf(queryKey))

            // Then
            count shouldBe 0
        }
    }

    /**
     * Tests for findListenersByKeysWithEvents functionality.
     * Used by ListenerEventService to find listeners matching an event with their accumulated events.
     */
    @Nested
    inner class FindListenersByKeysWithEventsTests {

        private val repository: ListenerRepository by lazy { getRepository() }
        private val eventRepository: ListenerEventRepository by lazy { getEventRepository() }

        @BeforeEach
        fun setup() = runTest {
            eventRepository.deleteAll()
            repository.deleteAll()
        }

        private fun createListener(strategy: ListenerStrategy): ListenerModel {
            return ListenerModel.random().copy(
                listenerStrategy = strategy,
            ).apply {
                closedAt = null
                outboxCompletedAt = null
                outboxFailedAt = null
                outboxDelayedUntil = null
                untilExpression = "\${ length > 2 }"
            }
        }

        private fun createQueryKey(listener: ListenerModel, strategy: ListenerStrategy? = null): ListenerQueryKey {
            return ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = null,
                filterIndex = null,
                listenerStrategy = strategy ?: listener.listenerStrategy
            )
        }

        private fun createEvent(
            listenerId: com.lemline.common.values.IDV7,
            eventId: String,
            filterIndex: Int = 0,
            sortKey: Int = 0
        ): ListenerEventModel {
            return ListenerEventModel(
                listenerId = listenerId,
                eventId = eventId,
                filterIndex = filterIndex,
                event = """{"type":"test.event","data":{"value":"$eventId"}}"""
            ).apply {
                this.sortKey = sortKey
            }
        }

        @Test
        fun `should return empty list for empty keys`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener exists
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            // When
            val result = repository.findListenersByKeysWithEvents(emptyList())

            // Then
            result shouldBe emptyList()
        }

        @Test
        fun `should return empty list when keys have non-ANY_UNTIL_EXPR strategies`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            // When: Keys have different strategies
            val queryKeys = listOf(
                createQueryKey(listener, ListenerStrategy.ONE),
                createQueryKey(listener, ListenerStrategy.ANY),
                createQueryKey(listener, ListenerStrategy.ALL)
            )
            val result = repository.findListenersByKeysWithEvents(queryKeys)

            // Then
            result shouldBe emptyList()
        }

        @Test
        fun `should find listener matching query key`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
        }

        @Test
        fun `should return accumulated events for listener`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener with multiple events
            // Note: Different filter_index values due to unique constraint (listener_id, filter_index)
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            val event1 = createEvent(listener.id, "event-1", filterIndex = 0, sortKey = 0)
            val event2 = createEvent(listener.id, "event-2", filterIndex = 1, sortKey = 1)
            val event3 = createEvent(listener.id, "event-3", filterIndex = 2, sortKey = 2)
            eventRepository.insert(event1)
            eventRepository.insert(event2)
            eventRepository.insert(event3)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result.size shouldBe 1
            result[0].second.size shouldBe 3
        }

        @Test
        fun `should return empty events list when listener has no events`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener with no events
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
            result[0].second shouldBe emptyList()
        }

        @Test
        fun `should not return already completed listeners`() = runTest {
            // Given: An already completed ANY_UNTIL_EXPR listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR).apply {
                closedAt = Clock.System.now()
            }
            repository.insert(listener)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result shouldBe emptyList()
        }

        @Test
        fun `should only return listeners with matching workflow info`() = runTest {
            // Given: Two listeners with different workflow info
            val listener1 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            val listener2 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener1)
            repository.insert(listener2)

            // When: Query with key matching only listener1
            val queryKey = createQueryKey(listener1, ListenerStrategy.ANY_UNTIL_EXPR)
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result.size shouldBe 1
            result[0].first.id shouldBe listener1.id
        }

        @Test
        fun `should find multiple listeners with multiple keys`() = runTest {
            // Given: Multiple ANY_UNTIL_EXPR listeners
            val listener1 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            val listener2 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener1)
            repository.insert(listener2)

            val queryKeys = listOf(
                createQueryKey(listener1, ListenerStrategy.ANY_UNTIL_EXPR),
                createQueryKey(listener2, ListenerStrategy.ANY_UNTIL_EXPR)
            )

            // When
            val result = repository.findListenersByKeysWithEvents(queryKeys)

            // Then
            result.size shouldBe 2
            result.map { it.first.id }.toSet() shouldBe setOf(listener1.id, listener2.id)
        }

        @Test
        fun `should filter keys and only process ANY_UNTIL_EXPR keys`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            // When: Keys include mixed strategies (only ANY_UNTIL_EXPR should be processed)
            val queryKeys = listOf(
                createQueryKey(listener, ListenerStrategy.ALL),
                createQueryKey(listener, ListenerStrategy.ONE),
                createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)
            )
            val result = repository.findListenersByKeysWithEvents(queryKeys)

            // Then
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
        }

        @Test
        fun `should not return listener with wrong strategy in database`() = runTest {
            // Given: Listeners with different strategies
            val oneListener = createListener(ListenerStrategy.ONE)
            val anyListener = createListener(ListenerStrategy.ANY)
            val allListener = createListener(ListenerStrategy.ALL)
            repository.insert(oneListener)
            repository.insert(anyListener)
            repository.insert(allListener)

            // When: Query with ANY_UNTIL_EXPR strategy
            val queryKeys = listOf(
                createQueryKey(oneListener, ListenerStrategy.ANY_UNTIL_EXPR),
                createQueryKey(anyListener, ListenerStrategy.ANY_UNTIL_EXPR),
                createQueryKey(allListener, ListenerStrategy.ANY_UNTIL_EXPR)
            )
            val result = repository.findListenersByKeysWithEvents(queryKeys)

            // Then: No listeners should be returned (strategy mismatch in database)
            result shouldBe emptyList()
        }

        @Test
        fun `should return events in order by created_at`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener with events added in specific order
            // Note: Different filter_index values due to unique constraint (listener_id, filter_index)
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            // Insert events (created_at is auto-generated in order)
            val event1 = createEvent(listener.id, "first", filterIndex = 0, sortKey = 0)
            val event2 = createEvent(listener.id, "second", filterIndex = 1, sortKey = 1)
            val event3 = createEvent(listener.id, "third", filterIndex = 2, sortKey = 2)
            eventRepository.insert(event1)
            eventRepository.insert(event2)
            eventRepository.insert(event3)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then: Events should be in insertion order (by created_at)
            result.size shouldBe 1
            result[0].second.size shouldBe 3
            result[0].second[0].contains("first") shouldBe true
            result[0].second[1].contains("second") shouldBe true
            result[0].second[2].contains("third") shouldBe true
        }

        @Test
        fun `should not return listener with different position`() = runTest {
            // Given: An ANY_UNTIL_EXPR listener
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            // When: Query with different position
            val queryKey = ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = com.lemline.common.values.NodePosition("/different/position"),
                correlationValuesJson = null,
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
            )
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result shouldBe emptyList()
        }

        @Test
        fun `should match listener with null correlation when query has correlation`() = runTest {
            // Given: A listener with null correlation values
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR).apply {
                correlationValues = null
            }
            repository.insert(listener)

            // When: Query with correlation values (should match null correlation in DB)
            val queryKey = ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = """{"orderId":"123"}""",
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
            )
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then: Should match (null correlation matches any)
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
        }

        @Test
        fun `should match listener with matching correlation values`() = runTest {
            // Given: A listener with specific correlation values
            val correlationJson = """{"orderId":"123"}"""
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR).apply {
                correlationValues = correlationJson
            }
            repository.insert(listener)

            // When: Query with same correlation values
            val queryKey = ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = correlationJson,
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
            )
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
        }

        @Test
        fun `should not match listener with different correlation values`() = runTest {
            // Given: A listener with specific correlation values
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR).apply {
                correlationValues = """{"orderId":"123"}"""
            }
            repository.insert(listener)

            // When: Query with different correlation values
            val queryKey = ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = """{"orderId":"456"}""",
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
            )
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then
            result shouldBe emptyList()
        }

        @Test
        fun `should not mix events between different listeners`() = runTest {
            // Given: Two listeners with their own events
            val listener1 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            val listener2 = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener1)
            repository.insert(listener2)

            val event1a = createEvent(listener1.id, "listener1-event-a", filterIndex = 0, sortKey = 0)
            val event1b = createEvent(listener1.id, "listener1-event-b", filterIndex = 1, sortKey = 1)
            val event2a = createEvent(listener2.id, "listener2-event-a", filterIndex = 0, sortKey = 0)
            val event2b = createEvent(listener2.id, "listener2-event-b", filterIndex = 1, sortKey = 1)
            eventRepository.insert(event1a)
            eventRepository.insert(event1b)
            eventRepository.insert(event2a)
            eventRepository.insert(event2b)

            // When: Query for both listeners
            val queryKeys = listOf(
                createQueryKey(listener1, ListenerStrategy.ANY_UNTIL_EXPR),
                createQueryKey(listener2, ListenerStrategy.ANY_UNTIL_EXPR)
            )
            val result = repository.findListenersByKeysWithEvents(queryKeys)

            // Then: Each listener should only have its own events
            result.size shouldBe 2

            val result1 = result.find { it.first.id == listener1.id }!!
            val result2 = result.find { it.first.id == listener2.id }!!

            result1.second.size shouldBe 2
            result1.second.all { it.contains("listener1") } shouldBe true
            result1.second.none { it.contains("listener2") } shouldBe true

            result2.second.size shouldBe 2
            result2.second.all { it.contains("listener2") } shouldBe true
            result2.second.none { it.contains("listener1") } shouldBe true
        }

        @Test
        fun `should be idempotent - multiple calls return same results`() = runTest {
            // Given: A listener with events
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR)
            repository.insert(listener)

            val event1 = createEvent(listener.id, "event-1", filterIndex = 0, sortKey = 0)
            val event2 = createEvent(listener.id, "event-2", filterIndex = 1, sortKey = 1)
            eventRepository.insert(event1)
            eventRepository.insert(event2)

            val queryKey = createQueryKey(listener, ListenerStrategy.ANY_UNTIL_EXPR)

            // When: Call multiple times
            val result1 = repository.findListenersByKeysWithEvents(listOf(queryKey))
            val result2 = repository.findListenersByKeysWithEvents(listOf(queryKey))
            val result3 = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then: All calls return identical results
            result1.size shouldBe 1
            result2.size shouldBe 1
            result3.size shouldBe 1

            result1[0].first.id shouldBe result2[0].first.id
            result2[0].first.id shouldBe result3[0].first.id

            result1[0].second shouldBe result2[0].second
            result2[0].second shouldBe result3[0].second
        }

        @Test
        fun `should match listener when query has no correlation`() = runTest {
            // Given: A listener with correlation values
            val listener = createListener(ListenerStrategy.ANY_UNTIL_EXPR).apply {
                correlationValues = """{"orderId":"123"}"""
            }
            repository.insert(listener)

            // When: Query without correlation (null)
            val queryKey = ListenerQueryKey(
                workflowInfo = listener.instanceMessage.workflowInfo,
                position = listener.instanceMessage.workflowState.nodePosition,
                correlationValuesJson = null,  // No correlation in query
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR
            )
            val result = repository.findListenersByKeysWithEvents(listOf(queryKey))

            // Then: Should match (no correlation filter applied)
            result.size shouldBe 1
            result[0].first.id shouldBe listener.id
        }
    }

    /**
     * Tests for batchMarkListenersCompleted functionality.
     * Used by ListenerEventService after until expression evaluation.
     */
    @Nested
    inner class BatchMarkListenersCompletedTests {

        private val repository: ListenerRepository by lazy { getRepository() }

        @BeforeEach
        fun setup() = runTest {
            repository.deleteAll()
        }

        private fun createPendingListener(): ListenerModel {
            return ListenerModel.random().copy(
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR,
            ).apply {
                closedAt = null
                outboxCompletedAt = null
                outboxFailedAt = null
                outboxDelayedUntil = null
            }
        }

        @Test
        fun `should return 0 for empty list`() = runTest {
            // Given: A pending listener exists
            val listener = createPendingListener()
            repository.insert(listener)

            // When
            val result = repository.batchMarkListenersCompleted(emptyList())

            // Then
            result shouldBe 0

            // Verify listener unchanged
            val unchanged = repository.findById(listener.id)
            unchanged?.closedAt shouldBe null
        }

        @Test
        fun `should mark single listener as completed`() = runTest {
            // Given: A pending listener
            val listener = createPendingListener()
            repository.insert(listener)

            // When
            val result = repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then
            result shouldBe 1

            val updated = repository.findById(listener.id)
            updated?.closedAt shouldNotBe null
        }

        @Test
        fun `should mark multiple listeners as completed`() = runTest {
            // Given: Multiple pending listeners
            val listener1 = createPendingListener()
            val listener2 = createPendingListener()
            val listener3 = createPendingListener()
            repository.insert(listener1)
            repository.insert(listener2)
            repository.insert(listener3)

            // When
            val result = repository.batchMarkListenersCompleted(
                listOf(listener1.id, listener2.id, listener3.id)
            )

            // Then
            result shouldBe 3

            repository.findById(listener1.id)?.closedAt shouldNotBe null
            repository.findById(listener2.id)?.closedAt shouldNotBe null
            repository.findById(listener3.id)?.closedAt shouldNotBe null
        }

        @Test
        fun `should skip already completed listeners`() = runTest {
            // Given: An already completed listener
            val listener = createPendingListener().apply {
                closedAt = Clock.System.now()
            }
            repository.insert(listener)

            // When
            val result = repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then: Should return 0 (already completed)
            result shouldBe 0
        }

        @Test
        fun `should mark outbox-completed listener if not yet completed`() = runTest {
            // Given: An outbox-completed but not yet closedAt listener
            val listener = createPendingListener().apply {
                outboxCompletedAt = Clock.System.now()
            }
            repository.insert(listener)

            // When
            val result = repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then: Should mark as completed (closedAt was null)
            result shouldBe 1
            repository.findById(listener.id)?.closedAt shouldNotBe null
        }

        @Test
        fun `should mark failed listener if not yet completed`() = runTest {
            // Given: A failed but not yet closedAt listener
            val listener = createPendingListener().apply {
                outboxFailedAt = Clock.System.now()
            }
            repository.insert(listener)

            // When
            val result = repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then: Should mark as completed (closedAt was null)
            result shouldBe 1
            repository.findById(listener.id)?.closedAt shouldNotBe null
        }

        @Test
        fun `should handle non-existent IDs gracefully`() = runTest {
            // Given: No listeners exist
            val nonExistentId = com.lemline.common.values.IDV7.random()

            // When
            val result = repository.batchMarkListenersCompleted(listOf(nonExistentId))

            // Then: Should return 0, no error
            result shouldBe 0
        }

        @Test
        fun `should return correct count for mixed valid and invalid IDs`() = runTest {
            // Given: Mix of pending, already completed, and non-existent
            val pending1 = createPendingListener()
            val pending2 = createPendingListener()
            val alreadyCompleted = createPendingListener().apply { closedAt = Clock.System.now() }
            repository.insert(pending1)
            repository.insert(pending2)
            repository.insert(alreadyCompleted)
            val nonExistentId = com.lemline.common.values.IDV7.random()

            // When
            val result = repository.batchMarkListenersCompleted(
                listOf(pending1.id, pending2.id, alreadyCompleted.id, nonExistentId)
            )

            // Then: Only pending listeners should be marked (already completed and non-existent skipped)
            result shouldBe 2

            repository.findById(pending1.id)?.closedAt shouldNotBe null
            repository.findById(pending2.id)?.closedAt shouldNotBe null
        }

        @Test
        fun `should be idempotent - second call returns 0`() = runTest {
            // Given: A pending listener
            val listener = createPendingListener()
            repository.insert(listener)

            // When: Call twice
            val result1 = repository.batchMarkListenersCompleted(listOf(listener.id))
            val result2 = repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then: First call marks it, second call is no-op
            result1 shouldBe 1
            result2 shouldBe 0
        }

        @Test
        fun `should not set outboxDelayedUntil`() = runTest {
            // Given: A pending listener with no outboxDelayedUntil
            val listener = createPendingListener()
            listener.outboxDelayedUntil shouldBe null
            repository.insert(listener)

            // When
            repository.batchMarkListenersCompleted(listOf(listener.id))

            // Then: outboxDelayedUntil should still be null
            val updated = repository.findById(listener.id)
            updated?.outboxDelayedUntil shouldBe null
        }
    }
}
