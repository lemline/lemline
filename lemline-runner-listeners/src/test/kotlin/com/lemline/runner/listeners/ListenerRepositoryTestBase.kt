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
}
