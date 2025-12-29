// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithOutbox
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test


@ExperimentalTime
infix fun Instant.shouldBeAfter(date: Instant) = (this > date) shouldBe true

/**
 * Test class for validating the behavior of `OutboxProcessor` through various test scenarios.
 *
 * This test class defines multiple test cases to ensure the functionality of the
 * `OutboxProcessor`, including message processing, retry logic, cleanup, and batch handling.
 *
 * Usage:
 * ```kotlin
 * class WaitOutboxProcessorTest : OutboxProcessorTest<WaitModel>(
 *     outboxRepository = { waitRepository },
 *     crudRepository = { waitRepository },
 *     idRepository = { waitRepository },
 *     databaseConfig = { testDatabaseConfig },
 *     modelClass = WaitModel::class,
 *     createTestModel = { payload -> WaitModel.random() }
 * )
 * ```
 *
 * @param T The generic type parameter representing the model class being tested.
 * @param outboxRepository Provider for outbox repository operations
 * @param crudRepository Provider for CRUD operations
 * @param idRepository Provider for ID-based operations
 * @param databaseConfig Provider for database configuration
 * @param modelClass KClass reference for MockK
 * @param createTestModel Factory function to create test entities
 */
@ExperimentalTime
@ExperimentalSerializationApi
abstract class OutboxProcessorTest<T>(
    outboxRepository: () -> WithOutboxRepository<T>,
    crudRepository: () -> WithCrudRepository<T>,
    idRepository: () -> WithIdRepository<T>,
    databaseConfig: () -> DatabaseConfig,
    private val modelClass: KClass<T>,
    private val createTestModel: (payload: String) -> T
) where T : WithOutbox, T : WithId {

    // Lazy-initialized repositories using providers
    private val outboxRepository: WithOutboxRepository<T> by lazy { outboxRepository() }
    private val crudRepository: WithCrudRepository<T> by lazy { crudRepository() }
    private val idRepository: WithIdRepository<T> by lazy { idRepository() }
    private val databaseConfig: DatabaseConfig by lazy { databaseConfig() }

    // Mock processor and relay for testing
    private val mockedProcessor = mockk<suspend (T) -> Unit>()
    private val outboxRelay: AbstractOutbox<T> by lazy {
        object : AbstractOutbox<T>() {
            override val jobName = "test-outbox"
            override val outboxRepository = this@OutboxProcessorTest.outboxRepository
            override val crudRepository = this@OutboxProcessorTest.crudRepository
            override val databaseConfig = this@OutboxProcessorTest.databaseConfig
            override val commandEmitter = mockk<CommandEmitter>()
            override val outboxConfig = object : OutboxConfig {
                override val every: Duration = Duration.INFINITE
                override val batchSize: Int = 10
                override val initialJitter: Duration = Duration.ZERO
                override val retryDelay: Duration = 1.seconds
                override val maxAttempts: Int = 3
            }
            override val enabled = true

            override suspend fun process(entity: T) {
                mockedProcessor(entity)
            }
        }
    }

    // Default test configuration
    private val batchSize = 10
    private val maxAttempts = 3
    private val retryDelay = 1.seconds // 1 second

    @BeforeEach
    fun setup() = runTest {
        // Reset mock before each test, default to success
        coEvery { mockedProcessor(any(modelClass)) } just Runs

        this@OutboxProcessorTest.crudRepository.deleteAll()
    }

    // --- Test methods --- //

    /**
     * **Scenario: **Tests the happy path where a single message is successfully processed.
     *
     * **Arrange:**
     * - Creates a single test entity using `createTestModel`.
     * - Persists the entity (initial status: PENDING).
     *
     * **Act:**
     * - Calls `outboxProcessor.process()`.
     *
     * **Assert:**
     * - Verifies the mocked processor function was called at least once.
     * - Fetches the entity from the DB.
     * - Asserts the entity status is now SENT.
     * - Asserts the attempt count is 0.
     * - Asserts the last error is null.
     */
    @Test
    fun `process should handle successful message processing`() = runTest {
        // Arrange
        val message = createTestModel("SuccessPayload")
        this@OutboxProcessorTest.crudRepository.insert(message)

        // Act
        outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)

        // Assert
        // Verify the mock was called at least once
        coVerify(exactly = 1) { mockedProcessor(any(modelClass)) }

        val processedMessage = idRepository.findById(message.id)
        processedMessage shouldNotBe null
        processedMessage?.outboxCompletedAt shouldNotBe null
        processedMessage?.outboxFailedAt shouldBe null
        processedMessage?.outboxAttemptCount shouldBe 1
        processedMessage?.outboxErrorClass shouldBe null
    }

    /**
     * **Scenario: **Tests the retry mechanism: initial failure, delay, successful retry.
     *
     * **Arrange:**
     * - Creates and persists a test entity.
     * - Mocks the processor function to throw an exception on the first call.
     *
     * **Act (1st call):**
     * - Calls `outboxProcessor.process()`.
     *
     * **Assert (1st call):**
     * - Verifies the processor was called once.
     * - Checks DB state: status PENDING, attemptCount 1, lastError set, delayedUntil updated.
     * - Checks the calculated delay matches expectations.
     *
     * **Arrange (for 2nd call):**
     * - Mocks the processor function to succeed.
     *
     * **Act (2nd call):**
     * - Simulates waiting for the delay.
     * - Calls `outboxProcessor.process()` again.
     *
     * **Assert (2nd call):**
     * - Verifies the processor was called twice in total.
     * - Checks DB state: status SENT, attemptCount still 1, lastError remains.
     */
    @Test
    fun `process should handle retry logic on first failure then success`() = runBlocking(Dispatchers.IO) {
        // Arrange
        val original = createTestModel("RetryPayload")
        this@OutboxProcessorTest.crudRepository.insert(original)

        val failureException = RuntimeException("Processing failed on purpose!")
        // Setup mock to fail the first time it's called in this sequence
        coEvery { mockedProcessor(any(modelClass)) } throws failureException

        // Act: First process call (fails)
        val now = Clock.System.now()
        outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)

        // Assert: First attempt failed - Check DB state
        coVerify(exactly = 1) { mockedProcessor(any(modelClass)) } // Verify it was called once
        val updated = idRepository.findById(original.id)!!

        updated.outboxCompletedAt shouldBe null
        updated.outboxFailedAt shouldBe null
        updated.outboxAttemptCount shouldBe 1
        updated.outboxErrorMessage shouldContain failureException.message!!

        // Calculate expected delay using exponential backoff - 20% jitter
        val expectedMinDelay = retryDelay.inWholeMilliseconds * 0.8
        updated.outboxDelayedUntil!! shouldBeAfter (now + expectedMinDelay.milliseconds)

        // waiting for the next attempt
        delay(updated.outboxDelayedUntil!! - now)

        // Arrange: Setup mock to succeed on subsequent calls
        coEvery { mockedProcessor(any(modelClass)) } just Runs

        // Act: Second process call (should succeed now)
        outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)

        // Assert: A second attempt succeeded - Check DB state
        coVerify(exactly = 2) { mockedProcessor(any(modelClass)) } // Verify it was called again
        val final = idRepository.findById(original.id)!!

        final.outboxCompletedAt shouldNotBe null
        final.outboxFailedAt shouldBe null
        final.outboxAttemptCount shouldBe 2
        final.outboxErrorMessage shouldContain failureException.message!! // Error remains

        Unit
    }

    /**
     * **Scenario: **Tests that an entity reaches FAILED status after max retry attempts.
     *
     * **Arrange:**
     * - Creates and persists a test entity.
     * - Mocks the processor function to *always* throw an exception.
     *
     * **Act & Assert (Loop):**
     * - Calls `process()` `maxAttempts` times.
     * - In each iteration, checks the DB state (status, attemptCount, lastError, delayedUntil).
     * - Waits for the calculated delay between attempts.
     * - On the final iteration, asserts the status becomes FAILED.
     *
     * **Assert (Final):**
     * - Verifies the processor was called exactly `maxAttempts` times.
     * - Verifies the final status in the DB is FAILED and attemptCount is `maxAttempts`.
     */
    @Test
    fun `process should mark message as FAILED after max attempts`() = runBlocking(Dispatchers.IO) {
        // Arrange
        val original = createTestModel("FailPayload")
        this@OutboxProcessorTest.crudRepository.insert(original)
        val originalDelayedUntil = original.outboxDelayedUntil!!

        val failureException = RuntimeException("Persistent failure!")
        // Set up the mock to always fail
        coEvery { mockedProcessor(any(modelClass)) } throws failureException

        // Act & Assert intermediate attempts by checking DB state
        var lastDelayedUntil = originalDelayedUntil
        for (attempt in 1..maxAttempts) {
            val now = Clock.System.now()
            // when
            outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)
            // then
            val updated = idRepository.findById(original.id)!!
            if (attempt < maxAttempts) {
                updated.outboxCompletedAt shouldBe null
                updated.outboxFailedAt shouldBe null
                updated.outboxAttemptCount shouldBe attempt
                updated.outboxErrorMessage shouldContain failureException.message!!
                updated.outboxDelayedUntil!! shouldBeAfter lastDelayedUntil
                // Calculate expected delay using exponential backoff - 20% jitter
                val expectedMinDelay = (retryDelay.inWholeMilliseconds * (1L shl (attempt - 1)) * 0.8).toLong()
                updated.outboxDelayedUntil!! shouldBeAfter (now + expectedMinDelay.milliseconds)
                lastDelayedUntil = updated.outboxDelayedUntil!!
                // waiting for the next attempt
                delay(updated.outboxDelayedUntil!! - now)
            } else {
                // Final attempt check
                updated.outboxCompletedAt shouldBe null
                updated.outboxFailedAt shouldNotBe null
                updated.outboxAttemptCount shouldBe maxAttempts
                updated.outboxErrorMessage shouldContain failureException.message!!
                updated.outboxDelayedUntil shouldBe lastDelayedUntil
            }
        }
    }

    /**
     * **Scenario: **Tests processing a batch of multiple entities successfully.
     *
     * **Arrange:**
     * - Creates and persists a list of 5 test entities.
     *
     * **Act:**
     * - Calls `outboxProcessor.process()` once (with batchSize >= 5).
     *
     * **Assert:**
     * - Verifies the processor function was called 5 times.
     * - Fetches the processed entities from the DB.
     * - Asserts the list size is 5.
     * - Asserts each entity in the list has status SENT and attemptCount 0.
     */
    @Test
    fun `process should handle batch processing correctly`() = runTest {
        // Arrange
        val messages = List(5) { createTestModel("batch_$it") }
        this@OutboxProcessorTest.crudRepository.insert(messages)

        // Act
        outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)

        // Assert
        coVerify(exactly = 5) { mockedProcessor(any(modelClass)) }
        val processedMessages = this@OutboxProcessorTest.crudRepository.listAll()
        processedMessages shouldHaveSize 5
        processedMessages.forEach { msg ->
            msg.outboxCompletedAt shouldNotBe null
            msg.outboxFailedAt shouldBe null
            msg.outboxAttemptCount shouldBe 1
        }
    }

    /**
     * **Scenario: **Tests `process()` behaviour when the outbox table is empty.
     *
     * **Arrange:**
     * - Ensures the repository is empty (via setup's `deleteAll`).
     *
     * **Act:**
     * - Calls `outboxProcessor.process()`.
     *
     * **Assert:**
     * - Verifies the processor function was never called.
     * - Verifies the repository count is still 0.
     */
    @Test
    fun `process should do nothing when outbox is empty`() = runTest {
        // Arrange: No messages persisted (due to setup)

        // Act
        outboxRelay.processEntities(batchSize, maxAttempts, retryDelay)

        // Assert
        coVerify(exactly = 0) { mockedProcessor(any(modelClass)) }
        this@OutboxProcessorTest.crudRepository.countAll() shouldBe 0
    }

}
