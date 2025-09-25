// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.common.logger.logger
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.FAILED
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import com.lemline.runner.outbox.OutboxRelay
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
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
import kotlin.time.Duration.Companion.days
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
 * @param T The generic type parameter representing the model class being tested.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class OutboxProcessorTest<T : OutboxModel> {

    // Abstract repository to be provided by subclasses
    abstract val failureRepository: FailureRepository

    // Abstract repository to be provided by subclasses
    abstract val outboxRepository: OutboxRepository<T>

    // Abstract Kotlin class reference needed for MockK
    abstract val modelClass: KClass<T>

    // Abstract factory method for creating test entities
    abstract fun createTestModel(payload: String = "{}"): T

    // Mock and processor using the generic type T
    private val mockedProcessor = mockk<suspend (T) -> Unit>()
    private val outboxRelay: OutboxRelay<T> by lazy {
        OutboxRelay(
            logger = logger(),
            failureRepository = failureRepository,
            outboxRepository = outboxRepository,
            relay = mockedProcessor,
        )
    }

    // Default test configuration
    private val batchSize = 10
    private val maxAttempts = 3
    private val initialDelay = 1.seconds // 1 second

    @BeforeEach
    fun setup() = runTest {
        // Reset mock before each test, default to success
        coEvery { mockedProcessor(any(modelClass)) } just Runs

        outboxRepository.deleteAll()
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
        val message = createTestModel(payload = "SuccessPayload")
        outboxRepository.insert(message)

        // Act
        outboxRelay.process(batchSize, maxAttempts, initialDelay)

        // Assert
        // Verify the mock was called at least once
        coVerify(exactly = 1) { mockedProcessor(any(modelClass)) }

        val processedMessage = outboxRepository.findById(message.id)
        processedMessage shouldNotBe null
        processedMessage?.outBoxStatus shouldBe SENT
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
        val original = createTestModel(payload = "RetryPayload")
        outboxRepository.insert(original)

        val failureException = RuntimeException("Processing failed on purpose!")
        // Setup mock to fail the first time it's called in this sequence
        coEvery { mockedProcessor(any(modelClass)) } throws failureException

        // Act: First process call (fails)
        val now = Clock.System.now()
        outboxRelay.process(batchSize, maxAttempts, initialDelay)

        // Assert: First attempt failed - Check DB state
        coVerify(exactly = 1) { mockedProcessor(any(modelClass)) } // Verify it was called once
        val updated = outboxRepository.findById(original.id)!!

        updated.outBoxStatus shouldBe PENDING
        updated.outboxAttemptCount shouldBe 1
        updated.outboxErrorMessage shouldContain failureException.message!!

        // Calculate expected delay using exponential backoff - 20% jitter
        val expectedMinDelay = initialDelay.inWholeMilliseconds * 0.8
        updated.outboxDelayedUntil!! shouldBeAfter (now + expectedMinDelay.milliseconds)

        // waiting for the next attempt
        delay(updated.outboxDelayedUntil!! - now)

        // Arrange: Setup mock to succeed on subsequent calls
        coEvery { mockedProcessor(any(modelClass)) } just Runs

        // Act: Second process call (should succeed now)
        outboxRelay.process(batchSize, maxAttempts, initialDelay)

        // Assert: A second attempt succeeded - Check DB state
        coVerify(exactly = 2) { mockedProcessor(any(modelClass)) } // Verify it was called again
        val final = outboxRepository.findById(original.id)!!

        final.outBoxStatus shouldBe SENT // Status updated
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
        val original = createTestModel(payload = "FailPayload")
        outboxRepository.insert(original)
        val originalDelayedUntil = original.outboxDelayedUntil!!

        val failureException = RuntimeException("Persistent failure!")
        // Set up the mock to always fail
        coEvery { mockedProcessor(any(modelClass)) } throws failureException

        // Act & Assert intermediate attempts by checking DB state
        var lastDelayedUntil = originalDelayedUntil
        for (attempt in 1..maxAttempts) {
            val now = Clock.System.now()
            // when
            outboxRelay.process(batchSize, maxAttempts, initialDelay)
            // then
            val updated = outboxRepository.findById(original.id)!!
            if (attempt < maxAttempts) {
                updated.outBoxStatus shouldBe PENDING
                updated.outboxAttemptCount shouldBe attempt
                updated.outboxErrorMessage shouldContain failureException.message!!
                updated.outboxDelayedUntil!! shouldBeAfter lastDelayedUntil
                // Calculate expected delay using exponential backoff - 20% jitter
                val expectedMinDelay = (initialDelay.inWholeMilliseconds * (1L shl (attempt - 1)) * 0.8).toLong()
                updated.outboxDelayedUntil!! shouldBeAfter (now + expectedMinDelay.milliseconds)
                lastDelayedUntil = updated.outboxDelayedUntil!!
                // waiting for the next attempt
                delay(updated.outboxDelayedUntil!! - now)
            } else {
                // Final attempt check
                updated.outBoxStatus shouldBe FAILED
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
        outboxRepository.insert(messages)

        // Act
        outboxRelay.process(batchSize, maxAttempts, initialDelay)

        // Assert
        coVerify(exactly = 5) { mockedProcessor(any(modelClass)) }
        val processedMessages = outboxRepository.listAll()
        processedMessages shouldHaveSize 5
        processedMessages.forEach { msg ->
            msg.outBoxStatus shouldBe SENT
            msg.outboxAttemptCount shouldBe 1
        }
    }

    /**
     * **Scenario: **Tests the cleanup logic removes old SENT entities, retaining others.
     *
     * **Arrange:**
     * - Creates and persists entities with different statuses (SENT, PENDING, FAILED).
     * - Updates the `delayedUntil` timestamp of some entities (including the old SENT one) to be older than the retention cutoff using a repository update.
     *
     * **Act:**
     * - Calls `outboxProcessor.cleanup()`.
     *
     * **Assert:**
     * - Verifies the old SENT entity was deleted (findById is null).
     * - Verifies the recent SENT, PENDING, and FAILED entities were retained (findById is not null).
     */
    @Test
    fun `cleanup should remove old SENT messages`() = runTest {
        // Arrange
        val retentionDelay = 7.days
        val wayBeforeCutoff = Clock.System.now() - 8.days

        // Create messages using abstract factory
        val oldSentMessage = createTestModel("old_sent").apply {
            outBoxStatus = SENT
            outboxDelayedUntil = wayBeforeCutoff
        }
        val recentSentMessage = createTestModel("recent_sent").apply {
            outBoxStatus = SENT
            outboxDelayedUntil = Clock.System.now()
        }
        val pendingMessage = createTestModel("pending").apply {
            outBoxStatus = PENDING
            outboxDelayedUntil = wayBeforeCutoff
        }
        val failedMessage = createTestModel("failed").apply {
            outBoxStatus = FAILED
            outboxDelayedUntil = wayBeforeCutoff
        }
        outboxRepository.insert(listOf(oldSentMessage, recentSentMessage, pendingMessage, failedMessage))

        // Act
        outboxRelay.cleanup(retentionDelay, 2) // Use small batch size for testing

        // Assert
        outboxRepository.findById(oldSentMessage.id) shouldBe null // Should be deleted
        outboxRepository.findById(recentSentMessage.id) shouldNotBe null // Should remain
        outboxRepository.findById(pendingMessage.id) shouldNotBe null // Should remain (not SENT)
        outboxRepository.findById(failedMessage.id) shouldNotBe null // Should remain (not SENT)
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
        outboxRelay.process(batchSize, maxAttempts, initialDelay)

        // Assert
        coVerify(exactly = 0) { mockedProcessor(any(modelClass)) }
        outboxRepository.countAll() shouldBe 0
    }

    /**
     * **Scenario: **Tests `cleanup()` behaviour when the outbox table is empty.
     *
     * **Arrange:**
     * - Ensures the repository is empty (via setup's `deleteAll`).
     *
     * **Act:**
     * - Calls `outboxProcessor.cleanup()`.
     *
     * **Assert:**
     * - Verifies the repository count is still 0.
     */
    @Test
    fun `cleanup should do nothing when outbox is empty`() = runTest {
        // Arrange: No messages persisted (due to setup)
        val retentionDelay = 7.days

        // Act
        outboxRelay.cleanup(retentionDelay, batchSize)

        // Assert
        outboxRepository.countAll() shouldBe 0
    }

    /**
     * **Scenario: **Tests that cleanup processes all messages, even if it requires more than 3 chunks.
     *
     * **Arrange:**
     * - Creates and persists a large number of SENT messages (more than 3 chunks worth)
     * - Sets all messages to be older than the retention cutoff
     *
     * **Act:**
     * - Calls `outboxProcessor.cleanup()` with a small batch size
     *
     * **Assert:**
     * - Verifies that all SENT messages were deleted
     * - Verifies that the cleanup process continued until all messages were processed
     */
    @Test
    fun `cleanup should process all messages even if it requires more than 3 chunks`() = runTest {
        // Arrange
        val afterDelay = 7.days
        val cutoff = Clock.System.now() - afterDelay - 1.days
        val wayBeforeCutoff = cutoff - 1.days

        // Create more messages than can be processed in 3 chunks
        val batchSize = 10 // Small batch size to ensure multiple chunks
        val totalMessages = batchSize * 50 // More than 3 chunks worth of messages
        val messages = List(totalMessages) { index ->
            createTestModel("message_$index").apply {
                outBoxStatus = SENT
                outboxDelayedUntil = wayBeforeCutoff
            }
        }

        // Persist all messages
        outboxRepository.insert(messages)

        // Act
        outboxRelay.cleanup(afterDelay, batchSize)

        // Assert
        outboxRepository.countAll() shouldBe 0
    }
}
