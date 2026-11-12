// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.UntilCondition
import com.lemline.core.workflows.CachedEventConsumption
import com.lemline.core.workflows.CachedEventFilter
import com.lemline.core.workflows.CachedListenTask
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.net.URI
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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

    @Nested
    inner class CompleteUntilTests {
        private fun rule(strategy: ListenStrategy, vararg types: String) =
            CachedEventConsumption(strategy, types.map {
                CachedEventFilter(EventFilter(type = it), emptyMap())
            })

        private suspend fun listener(
            rule: CachedEventConsumption,
            foreach: Boolean = false,
            template: ListenerModel? = null,
        ): ListenerModel {
            val original = template?.copy(
                id = IDV7.random(),
                instanceMessage = template.instanceMessage.copy(workflowState = template.instanceMessage.workflowState.copy(
                    nodeStack = template.instanceMessage.workflowState.nodeStack.duplicate(WorkflowId.random(), Clock.System.now())
                ))
            ) ?: ListenerModel.random()
            val config = ListenConfig(
                strategy = ListenStrategy.ANY,
                filters = emptyList(), until = UntilCondition.Event(rule),
            )
            val model = original.copy(
                instanceMessage = original.instanceMessage.copy(workflowState = original.instanceMessage.workflowState.copy(config = config)),
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EVENT, timeoutAt = null,
                correlationValues = null, hasUntil = true, hasForeach = foreach, filtersCount = 0,
            )
            getRepository().insert(model) shouldBe 1
            return model
        }

        private fun service(model: ListenerModel): ListenerEventService {
            val condition = model.instanceMessage.workflowState.config.until
            val until = (condition as? UntilCondition.Event)?.consumption
            val task = CachedListenTask(
                workflowInfo = model.workflowInfo, nodePosition = model.nodePosition,
                consumption = CachedEventConsumption(ListenStrategy.ANY, emptyList(),
                    untilExpression = (condition as? UntilCondition.Expression)?.expression, untilEvents = until),
                readAs = ListenAndReadAs.DATA,
                hasForeach = model.hasForeach,
            )
            val definitions = io.mockk.mockk<DefinitionListenService>()
            io.mockk.every { definitions.findMatchingListenTasks(any(), any()) } returns listOf(MatchingListenTask(task, null, 0))
            io.mockk.every { definitions.findMatchingUntilEvents(any()) } answers {
                if (until?.mayMatch(firstArg()) == true) listOf(MatchingListenTaskUntilEvent(task)) else emptyList()
            }
            return ListenerEventService().apply {
                listenerRepository = getRepository()
                listenerEventRepository = getEventRepository()
                definitionListenService = definitions
                databaseConfig = getDatabaseConfig()
            }
        }

        private fun event(type: String, id: String = IDV7.random().toString(), source: String = "https://tests") =
            CloudEventBuilder.v1().withId(id).withSource(URI(source))
                .withType(type).withData("application/json", "{}".toByteArray()).build()

        @Test fun `until all persists partial progress across service restart and excludes final event`() = runTest {
            val model = listener(rule(ListenStrategy.ALL, "stop1", "stop2"), foreach = true)
            val first = event("stop1")
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            getRepository().findById(model.id)!!.closedAt shouldBe null
            getRepository().findById(model.id)!!.untilProgress shouldNotBe null
            service(model).handleCloudEvent(event("data"), model.workflowInfo.namespace)
            service(model).handleCloudEvent(event("stop2"), model.workflowInfo.namespace)
            getRepository().findById(model.id)!!.closedAt shouldNotBe null
            val events = getEventRepository().listAll().filter { it.listenerId == model.id }
            // A committed delivery retried by a fresh service neither advances until
            // nor inserts another main-stream/foreach entry.
            events.size shouldBe 2
            events.none { CloudEventService.deserialize(it.event).type == "stop2" } shouldBe true
            events.all { !it.foreachCompleted } shouldBe true
            // Closing does not bypass pending foreach work.
            getRepository().findById(model.id)!!.outboxDelayedUntil shouldBe null
        }

        @Test fun `committed receipts distinguish sources and subscriptions and survive event cleanup`() = runTest {
            val model = listener(rule(ListenStrategy.ALL, "stop1", "stop2"))
            val first = event("data", "shared-id", "https://source-a")
            val second = event("data", "shared-id", "https://source-b")
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            service(model).handleCloudEvent(second, model.workflowInfo.namespace)
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            service(model).handleCloudEvent(second, model.workflowInfo.namespace)
            getEventRepository().listAll().count { it.listenerId == model.id } shouldBe 2
            getEventRepository().findCompletedOutputsByListeners(listOf(model.id))[model.id]!!.size shouldBe 2

            // A new subscription to the same definition must still receive this event.
            val other = listener(rule(ListenStrategy.ALL, "stop1", "stop2"), template = model)
            service(other).handleCloudEvent(first, other.workflowInfo.namespace)
            getEventRepository().listAll().count { it.listenerId == model.id } shouldBe 2
            getEventRepository().listAll().count { it.listenerId == other.id } shouldBe 1

            // Collection cleanup must not erase the durable delivery history.
            getEventRepository().listAll().filter { it.listenerId == model.id }.forEach {
                getEventRepository().delete(it)
            }
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            getEventRepository().listAll().count { it.listenerId == model.id } shouldBe 0
        }

        @Test fun `concurrent redelivery after commit creates only one foreach entry`() = runTest {
            val model = listener(rule(ListenStrategy.ONE, "stop"), foreach = true)
            val delivered = event("data")
            service(model).handleCloudEvent(delivered, model.workflowInfo.namespace)
            coroutineScope {
                listOf(async { service(model).handleCloudEvent(delivered, model.workflowInfo.namespace) },
                    async { service(model).handleCloudEvent(delivered, model.workflowInfo.namespace) }).awaitAll()
            }
            val rows = getEventRepository().listAll().filter { it.listenerId == model.id }
            rows.size shouldBe 1
            rows.single().foreachCompleted shouldBe false
            val closing = event("stop")
            service(model).handleCloudEvent(closing, model.workflowInfo.namespace)
            service(model).handleCloudEvent(closing, model.workflowInfo.namespace)
            getEventRepository().listAll().count { it.listenerId == model.id } shouldBe 1
            getRepository().findById(model.id)!!.closedAt shouldNotBe null
        }

        @Test fun `committed redelivery cannot prematurely satisfy an until expression`() = runTest {
            val original = listener(rule(ListenStrategy.ONE, "stop"))
            val config = original.instanceMessage.workflowState.config.copy(until = UntilCondition.Expression("length >= 2"))
            val model = original.copy(
                instanceMessage = original.instanceMessage.copy(workflowState = original.instanceMessage.workflowState.copy(config = config)),
                listenerStrategy = ListenerStrategy.ANY_UNTIL_EXPR,
                untilExpression = "length >= 2",
            )
            getRepository().update(model)
            val first = event("data")
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            service(model).handleCloudEvent(first, model.workflowInfo.namespace)
            getRepository().findById(model.id)!!.closedAt shouldBe null
            getEventRepository().listAll().count { it.listenerId == model.id } shouldBe 1
            service(model).handleCloudEvent(event("data"), model.workflowInfo.namespace)
            getRepository().findById(model.id)!!.closedAt shouldNotBe null
        }

        @Test fun `foreach queue distinguishes equal ids from different sources`() = runTest {
            val model = listener(rule(ListenStrategy.ONE, "stop"), foreach = true)
            service(model).handleCloudEvent(event("data", "same", "https://a"), model.workflowInfo.namespace)
            service(model).handleCloudEvent(event("data", "same", "https://b"), model.workflowInfo.namespace)
            getEventRepository().markReadyForForeach(100)
            val rows = getEventRepository().listAll().filter { it.listenerId == model.id }.sortedBy { it.sortKey }
            rows.size shouldBe 2
            rows[0].outboxDelayedUntil shouldNotBe null
            rows[1].outboxDelayedUntil shouldBe null
            rows[0].outboxAttemptCount = 7
            getEventRepository().update(rows[0]) shouldBe 1
            getEventRepository().markForeachCompleted(model.id, rows[0].sortKey, "first") shouldBe 1
            getEventRepository().markReadyForForeach(100)
            val updated = getEventRepository().listAll().filter { it.listenerId == model.id }.sortedBy { it.sortKey }
            updated[1].outboxAttemptCount shouldBe 0
            updated[1].outboxDelayedUntil shouldNotBe null
            getEventRepository().markForeachCompleted(model.id, rows[1].sortKey, "second") shouldBe 1
            getEventRepository().findCompletedOutputsByListeners(listOf(model.id))[model.id] shouldBe listOf("first", "second")
        }

        @Test fun `until any accepts second filter and wildcard can terminate before first data`() = runTest {
            for (rule in listOf(rule(ListenStrategy.ANY, "never", "stop"), rule(ListenStrategy.ANY))) {
                val model = listener(rule)
                service(model).handleCloudEvent(event("stop"), model.workflowInfo.namespace)
                getRepository().findById(model.id)!!.closedAt shouldNotBe null
                getEventRepository().listAll().filter { it.listenerId == model.id }.size shouldBe 0
            }
        }

        @Test fun `termination all progress is independent for every instance`() = runTest {
            val until = rule(ListenStrategy.ALL, "stop1", "stop2")
            val first = listener(until)
            service(first).handleCloudEvent(event("stop1"), first.workflowInfo.namespace)
            val second = listener(until, template = first)
            service(first).handleCloudEvent(event("stop2"), first.workflowInfo.namespace)
            getRepository().findById(first.id)!!.closedAt shouldNotBe null
            getRepository().findById(second.id)!!.closedAt shouldBe null
            service(second).handleCloudEvent(event("stop1"), second.workflowInfo.namespace)
            getRepository().findById(second.id)!!.closedAt shouldNotBe null
        }

        @Test fun `failure after until evaluation rolls back both progress and closure before retry`() = runTest {
            for (until in listOf(
                rule(ListenStrategy.ALL, "stop1", "stop2"),
                rule(ListenStrategy.ONE, "stop1"),
            )) {
                val model = listener(until)
                val event = event("stop1")
                val failing = io.mockk.spyk(getEventRepository())
                io.mockk.coEvery { failing.batchInsertForAllAnyUntil(any(), any(), any(), any()) } throws IllegalStateException("simulated crash")
                val worker = service(model).apply { listenerEventRepository = failing }
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    worker.handleCloudEvent(event, model.workflowInfo.namespace)
                }
                val rolledBack = getRepository().findById(model.id)!!
                rolledBack.untilProgress shouldBe null
                rolledBack.closedAt shouldBe null
                getEventRepository().listAll().filter { it.listenerId == model.id }.size shouldBe 0
                // A fresh worker processes the broker retry using database state only.
                service(model).handleCloudEvent(event, model.workflowInfo.namespace)
                if (until.strategy == ListenStrategy.ALL) {
                    getRepository().findById(model.id)!!.closedAt shouldBe null
                    service(model).handleCloudEvent(event("stop2"), model.workflowInfo.namespace)
                }
                getRepository().findById(model.id)!!.closedAt shouldNotBe null
            }
        }

        @Test fun `concurrent termination events preserve all filter progress`() = runTest {
            val model = listener(rule(ListenStrategy.ALL, "stop1", "stop2"))
            kotlinx.coroutines.coroutineScope {
                val first = async(kotlinx.coroutines.Dispatchers.IO) {
                    service(model).handleCloudEvent(event("stop1"), model.workflowInfo.namespace)
                }
                val second = async(kotlinx.coroutines.Dispatchers.IO) {
                    service(model).handleCloudEvent(event("stop2"), model.workflowInfo.namespace)
                }
                first.await()
                second.await()
            }
            getRepository().findById(model.id)!!.closedAt shouldNotBe null
        }

    }

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
            listenerId: IDV7,
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
            val nonExistentId = IDV7.random()

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
            val nonExistentId = IDV7.random()

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
