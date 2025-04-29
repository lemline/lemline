// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.FAILED
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import com.lemline.runner.outbox.OutboxProcessor
import com.lemline.runner.repositories.OutboxRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Abstract base class for testing the core logic of [OutboxProcessor].
 *
 * This class provides a generic test suite that covers common outbox scenarios,
 * independent of the specific entity (`OutboxModel`) being processed.
 * Concrete subclasses must provide:
 *  - The specific [OutboxRepository] implementation.
 *  - The [KClass] of the entity being tested.
 *  - An implementation of `createTestModel` to generate test entities.
 *
 * The following scenarios are tested:
 * - `process should handle successful message processing`: Happy path, message processed and marked SENT.
 * - `process should handle retry logic on first failure then success`: Message fails once, is retried after delay, then succeeds.
 * - `process should mark message as FAILED after max attempts`: Message fails repeatedly and is marked FAILED.
 * - `process should handle batch processing correctly`: Multiple messages are processed successfully in one batch.
 * - `cleanup should remove old SENT messages`: Old SENT messages are deleted, others are retained.
 * - `process should do nothing when outbox is empty`: Handles empty table gracefully during processing.
 * - `cleanup should do nothing when outbox is empty`: Handles empty table gracefully during cleanup.
 *
 * It utilizes MockK for mocking the actual processing function (`processor`) passed to `OutboxProcessor`
 * and Kotest for assertions against the database state managed via a concrete `OutboxRepository` provided by the subclass.
 *
 * @param T The specific type of [OutboxModel] entity being tested.
 */
internal abstract class OutboxProcessorTest<T : OutboxModel> {

    // Abstract repository to be provided by subclasses
    abstract val testRepository: OutboxRepository<T>

    // Abstract Kotlin class reference needed for MockK
    abstract val modelClass: KClass<T>

    // Abstract factory method for creating test entities
    abstract fun createTestModel(payload: String = "{}"): T

    // Mock and processor using the generic type T
    protected val mockProcessorFunction = mockk<(T) -> Unit>()
    protected lateinit var outboxProcessor: OutboxProcessor<T>

    // Default test configuration (can be overridden by subclasses if needed)
    protected open val batchSize = 10
    protected open val maxAttempts = 3
    protected open val initialDelay: Duration = Duration.ofSeconds(1) // 1 second

    @BeforeEach
    @Transactional // Keep transaction for setup actions like deleteAll
    fun setUp() {
        // Reset mock before each test, default to success
        every { mockProcessorFunction(any(modelClass)) } just Runs
        outboxProcessor = OutboxProcessor(
            logger = LoggerFactory.getLogger(this::class.java),
            repository = testRepository,
            processor = mockProcessorFunction,
        )
        testRepository.deleteAll()
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
    @Transactional
    fun `process should handle successful message processing`() {
        // Arrange
        val message = createTestModel(payload = "SuccessPayload")
        testRepository.persist(message)

        // Act
        outboxProcessor.process(batchSize, maxAttempts, initialDelay)

        // Assert
        // Verify the mock was called (at least once, type checked by any())
        verify(atLeast = 1) { mockProcessorFunction(any(modelClass)) }

        val processedMessage = testRepository.findById(message.id)
        processedMessage shouldNotBe null
        processedMessage!!.status shouldBe SENT
        processedMessage.attemptCount shouldBe 1
        processedMessage.lastError shouldBe null
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
    @Transactional
    fun `process should handle retry logic on first failure then success`() {
        // Arrange
        val message = createTestModel(payload = "RetryPayload")
        testRepository.persist(message)
        val messageId = message.id
        val initialDelayedUntil = message.delayedUntil

        val failureException = RuntimeException("Processing failed!")
        // Setup mock to fail the first time it's called in this sequence
        every { mockProcessorFunction(any(modelClass)) } throws failureException

        // Act: First process call (fails)
        outboxProcessor.process(batchSize, maxAttempts, initialDelay)

        // Assert: First attempt failed - Check DB state
        verify(exactly = 1) { mockProcessorFunction(any(modelClass)) } // Verify it was called once
        val failedMessage = testRepository.findById(messageId)
        failedMessage shouldNotBe null
        val capturedFailedMessage = failedMessage!!
        capturedFailedMessage.status shouldBe PENDING
        capturedFailedMessage.attemptCount shouldBe 1
        capturedFailedMessage.lastError shouldBe failureException.message
        capturedFailedMessage.delayedUntil shouldBeAfter initialDelayedUntil

        val delayMillis = Duration.between(Instant.now(), capturedFailedMessage.delayedUntil).toMillis()
        val tolerance = initialDelay.toMillis() * 0.3
        delayMillis.toDouble() shouldBe (initialDelay.toMillis().toDouble() plusOrMinus tolerance)

        // Arrange: Setup mock to succeed on subsequent calls
        every { mockProcessorFunction(any(modelClass)) } just Runs

        // Act: Second process call (should succeed now)
        if (delayMillis > 0) Thread.sleep(delayMillis + 300) else Thread.sleep(300)
        outboxProcessor.process(batchSize, maxAttempts, initialDelay)

        // Assert: Second attempt succeeded - Check DB state
        verify(exactly = 2) { mockProcessorFunction(any(modelClass)) } // Verify it was called again
        val succeededMessage = testRepository.findById(messageId)
        succeededMessage shouldNotBe null
        val capturedSucceededMessage = succeededMessage!!
        capturedSucceededMessage.status shouldBe SENT // Status updated
        capturedSucceededMessage.attemptCount shouldBe 2
        capturedSucceededMessage.lastError shouldBe failureException.message // Error remains
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
    @Transactional
    fun `process should mark message as FAILED after max attempts`() {
        // Arrange
        val message = createTestModel(payload = "FailPayload")
        testRepository.persist(message)
        val initialDelayedUntil = message.delayedUntil

        val failureException = RuntimeException("Persistent failure!")
        // Set up the mock to always fail
        every { mockProcessorFunction(any(modelClass)) } throws failureException

        // Act & Assert intermediate attempts by checking DB state
        var lastDelayedUntil = initialDelayedUntil
        for (attempt in 1..maxAttempts) {
            outboxProcessor.process(batchSize, maxAttempts, initialDelay)
            val intermediateMessage = testRepository.findById(message.id)
            intermediateMessage shouldNotBe null
            val capturedIntermediate = intermediateMessage!!

            if (attempt < maxAttempts) {
                capturedIntermediate.status shouldBe PENDING
                capturedIntermediate.attemptCount shouldBe attempt
                capturedIntermediate.lastError shouldBe failureException.message
                capturedIntermediate.delayedUntil shouldBeAfter lastDelayedUntil
                lastDelayedUntil = capturedIntermediate.delayedUntil
                val lastAttemptDelayMillis =
                    Duration.between(Instant.now(), capturedIntermediate.delayedUntil).toMillis()
                val expectedMinDelay = initialDelay.toMillis() * (1L shl (attempt - 1)) * 0.7
                lastAttemptDelayMillis shouldBeGreaterThan (expectedMinDelay.toLong() - 200)
                if (lastAttemptDelayMillis > 0) Thread.sleep(lastAttemptDelayMillis + 300)
            } else { // Final attempt check
                capturedIntermediate.status shouldBe FAILED
                capturedIntermediate.attemptCount shouldBe maxAttempts
                capturedIntermediate.lastError shouldBe failureException.message
            }
        }

        // Assert: Final state verification and call count
        verify(exactly = maxAttempts) { mockProcessorFunction(any(modelClass)) }
        val finalMessage = testRepository.findById(message.id)
        finalMessage shouldNotBe null
        finalMessage!!.status shouldBe FAILED
        finalMessage.attemptCount shouldBe maxAttempts
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
    @Transactional
    fun `process should handle batch processing correctly`() {
        // Arrange
        val messages = List(5) { createTestModel("batch_$it") }
        testRepository.persist(messages)

        // Act
        outboxProcessor.process(batchSize, maxAttempts, initialDelay)

        // Assert
        verify(exactly = 5) { mockProcessorFunction(any(modelClass)) }
        val processedMessages = testRepository.listAll()
        processedMessages shouldHaveSize 5
        processedMessages.forEach { msg ->
            msg.status shouldBe SENT
            msg.attemptCount shouldBe 1
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
    @Transactional
    fun `cleanup should remove old SENT messages`() {
        // Arrange
        val retentionDelay = Duration.ofDays(7)
        val cutoff = Instant.now().minusSeconds(retentionDelay.toSeconds() + 24 * 60 * 60)
        val wayBeforeCutoff = cutoff.minus(1, ChronoUnit.DAYS)

        // Create messages using abstract factory
        val oldSentMessage = createTestModel("old_sent").apply { status = SENT }
        val recentSentMessage = createTestModel("recent_sent").apply { status = SENT }
        val pendingMessage = createTestModel("pending") // Default PENDING status
        val failedMessage = createTestModel("failed").apply { status = FAILED }

        // Persist all messages
        testRepository.persist(listOf(oldSentMessage, recentSentMessage, pendingMessage, failedMessage))

        // Manually update the 'delayedUntil' timestamp for the old messages *after* persisting
        // to simulate them being older than the cutoff for cleanup purposes.
        oldSentMessage.delayedUntil = wayBeforeCutoff
        pendingMessage.delayedUntil = wayBeforeCutoff
        failedMessage.delayedUntil = wayBeforeCutoff
        testRepository.persist(listOf(oldSentMessage, pendingMessage, failedMessage))

        val oldSentId = oldSentMessage.id
        val recentSentId = recentSentMessage.id
        val pendingId = pendingMessage.id
        val failedId = failedMessage.id

        // Act
        outboxProcessor.cleanup(retentionDelay, 2) // Use small batch size for testing

        // Assert
        testRepository.findById(oldSentId) shouldBe null // Should be deleted
        testRepository.findById(recentSentId) shouldNotBe null // Should remain
        testRepository.findById(pendingId) shouldNotBe null // Should remain (not SENT)
        testRepository.findById(failedId) shouldNotBe null // Should remain (not SENT)
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
    @Transactional
    fun `process should do nothing when outbox is empty`() {
        // Arrange: No messages persisted (due to setup)

        // Act
        outboxProcessor.process(batchSize, maxAttempts, initialDelay)

        // Assert
        verify(exactly = 0) { mockProcessorFunction(any(modelClass)) }
        testRepository.count() shouldBe 0
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
    @Transactional
    fun `cleanup should do nothing when outbox is empty`() {
        // Arrange: No messages persisted (due to setup)
        val retentionDelay = Duration.ofDays(7)

        // Act
        outboxProcessor.cleanup(retentionDelay, batchSize)

        // Assert
        testRepository.count() shouldBe 0
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
    @Transactional
    fun `cleanup should process all messages even if it requires more than 3 chunks`() {
        // Arrange
        val afterDelay = Duration.ofDays(7)
        val cutoff = Instant.now().minusSeconds(afterDelay.toSeconds() + 24 * 60 * 60)
        val wayBeforeCutoff = cutoff.minus(1, ChronoUnit.DAYS)

        // Create more messages than can be processed in 3 chunks
        val batchSize = 10 // Small batch size to ensure multiple chunks
        val totalMessages = batchSize * 50 // More than 3 chunks worth of messages
        val messages = List(totalMessages) { index ->
            createTestModel("message_$index").apply {
                status = SENT
                delayedUntil = wayBeforeCutoff
            }
        }

        // Persist all messages
        testRepository.persist(messages)

        // Act
        outboxProcessor.cleanup(afterDelay, batchSize)

        // Assert
        testRepository.count() shouldBe 0
    }
}
