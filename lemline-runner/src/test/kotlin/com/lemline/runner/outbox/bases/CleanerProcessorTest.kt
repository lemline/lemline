// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.runner.config.LemlineConfiguration.TablesConfig.CleanerConfig
import com.lemline.runner.models.bases.CleanerModelBase
import com.lemline.runner.outbox.bases.RunStatus.DONE
import com.lemline.runner.outbox.bases.RunStatus.FAILED
import com.lemline.runner.outbox.bases.RunStatus.PENDING
import com.lemline.runner.random.random
import com.lemline.runner.repositories.bases.CleanerRepositoryBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import java.util.*
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
internal abstract class CleanerProcessorTest<T : CleanerModelBase> {

    // Abstract repository to be provided by subclasses
    abstract val cleanerRepository: CleanerRepositoryBase<T>

    // Abstract Kotlin class reference needed for MockK
    abstract val modelClass: KClass<T>

    // Abstract factory method for creating test entities
    abstract fun createTestModel(
        runStatus: RunStatus = RunStatus.random(),
        runAt: Instant = Clock.System.now()
    ): T

    // Mock and processor using the generic type T
    private val mockedProcessor = mockk<suspend (T) -> Unit>()

    // Create a concrete implementation class instead of anonymous object
    private class TestCleanerConfig : CleanerConfig {
        override fun enabled() = Optional.of(true)
        override fun every() = "10s"
        override fun batchSize() = 10
        override fun after() = "7d"
        override fun gracePeriod() = "5s"
    }

    private val cleaner by lazy {
        Cleaner(
            cleanerRepository = cleanerRepository,
            cleanerConfig = TestCleanerConfig()
        )
    }

    @BeforeEach
    fun setup() = runTest {
        // Reset mock before each test, default to success
        coEvery { mockedProcessor(any(modelClass)) } just Runs

        cleanerRepository.deleteAll()
    }

    // --- Test methods --- //


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
        val oldSentMessage = createTestModel(
            runStatus = DONE,
            runAt = wayBeforeCutoff
        )
        val recentSentMessage = createTestModel(
            runStatus = DONE,
            runAt = Clock.System.now()
        )
        val pendingMessage = createTestModel(
            runStatus = PENDING,
            runAt = wayBeforeCutoff
        )
        val failedMessage = createTestModel(
            runStatus = FAILED,
            runAt = wayBeforeCutoff
        )
        cleanerRepository.insert(listOf(oldSentMessage, recentSentMessage, pendingMessage, failedMessage))

        // Act
        //cleaner.cleanup(retentionDelay, 2) // Use small batch size for testing

        cleaner.run()

        // Assert
        cleanerRepository.findById(oldSentMessage.id) shouldBe null // Should be deleted
        cleanerRepository.findById(recentSentMessage.id) shouldNotBe null // Should remain
        cleanerRepository.findById(pendingMessage.id) shouldNotBe null // Should remain (not SENT)
        cleanerRepository.findById(failedMessage.id) shouldNotBe null // Should remain (not SENT)
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
    fun `cleaning should do nothing when outbox is empty`() = runTest {
        // Arrange: No messages persisted (due to setup)

        // Act
        cleaner.run()

        // Assert
        coVerify(exactly = 0) { mockedProcessor(any(modelClass)) }
        cleanerRepository.countAll() shouldBe 0
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
        cleaner.run()

        // Assert
        cleanerRepository.countAll() shouldBe 0
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
            createTestModel(
                runStatus = DONE,
                runAt = wayBeforeCutoff
            )
        }

        // Persist all messages
        cleanerRepository.insert(messages)

        // Act
        // cleaner.cleanup(afterDelay, batchSize)
        cleaner.run()

        // Assert
        cleanerRepository.countAll() shouldBe 0
    }
}
