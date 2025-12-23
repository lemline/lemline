// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.InstanceRepositoryTest
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
    inner class InstanceTests : InstanceRepositoryTest<ListenerModel>(
        instanceRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getWorkflowId = { it.workflowId }
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
                completedAt = null
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
            updated!!.completedAt shouldNotBe null
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
            updated!!.completedAt shouldBe null
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
            updated!!.completedAt shouldBe null
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
                completedAt = Clock.System.now()
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
            repository.findById(listener1.id)!!.completedAt shouldNotBe null
            repository.findById(listener2.id)!!.completedAt shouldNotBe null
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
            repository.findById(listener.id)!!.completedAt shouldNotBe null
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
}
