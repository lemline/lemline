// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox.bases

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxMessage
import com.lemline.worker.outbox.OutboxProcessor
import com.lemline.worker.outbox.OutboxRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture

/**
 * Abstract base class for outbox tests that works with both PostgreSQL and MySQL.
 * This class serves as a generic test framework for both WaitOutbox and RetryOutbox.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
abstract class AbstractOutboxTest<T> where T : OutboxMessage {

    @Inject
    protected lateinit var entityManager: EntityManager

    protected val emitter = mockk<Emitter<String>>()

    /**
     * Entity class name to use for cleanup queries
     */
    protected abstract val entity: Class<T>


    abstract val repository: OutboxRepository<out T>

    /**
     * The outbox processor being tested (either WaitOutbox or RetryOutbox)
     */
    abstract val processor: OutboxProcessor<out T>

    /**
     * Maximum number of retry attempts before failing a message
     */
    protected open val maxAttempts: Int = 3

    /**
     * Create a new model instance of the appropriate type for testing
     */
    private fun createModel(): T = entity.newInstance()


    /**
     * Find a model by ID
     */
    private fun findModel(id: String?): T = entityManager.find(entity, id)

    /**
     * Process pending messages in the outbox
     */
    protected abstract fun processOutbox()

    /**
     * Clean up old sent messages
     */
    protected abstract fun cleanupOutbox()

    @BeforeEach
    @Transactional
    open fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM ${entity.simpleName}").executeUpdate()
        entityManager.flush()

        // Reset mock behavior
        clearMocks(emitter)
        every { emitter.send(any()) } returns CompletableFuture.completedStage(null)
    }

    protected open fun getWorkflowMessage() = WorkflowMessage.newInstance(
        "test-workflow", "1.0.0", "ID", JsonPrimitive("description")
    )

    private fun getWorkflowMessageJson(): String {
        val workflowMessage = getWorkflowMessage()
        return LemlineJson.encodeToString(workflowMessage)
    }

    private fun getBasicWorkflowMessage() = WorkflowMessage(
        "test-workflow", "1.0.0", emptyMap(), NodePosition(listOf())
    )

    private fun getPastInstant(minutes: Long = 1): Instant =
        Instant.now().minus(minutes, ChronoUnit.MINUTES)

    private fun getFutureInstant(minutes: Long = 1): Instant =
        Instant.now().plus(minutes, ChronoUnit.MINUTES)

    private fun getPastInstantDays(days: Long = 1): Instant =
        Instant.now().minus(days, ChronoUnit.DAYS)

    @Test
    @Transactional
    fun `Outbox should process pending messages and mark them as sent`() {
        // Given
        val messageJson = getWorkflowMessageJson()
        val message = createModel().apply {
            message = messageJson
            status = OutBoxStatus.PENDING
            delayedUntil = getPastInstant()
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // When
        processOutbox()

        // Then
        // Verify message was sent
        verify { emitter.send(messageJson) }

        // Verify message status was updated
        val updatedMessage = findModel(message.id)
        assertEquals(OutBoxStatus.SENT, updatedMessage.status)
        assertEquals(0, updatedMessage.attemptCount)
    }

    @Test
    @Transactional
    fun `Outbox should mark message for retry after processing failure`() {
        // Given
        val message = createModel().apply {
            message = "invalid json"
            status = OutBoxStatus.PENDING
            delayedUntil = getPastInstant()
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = findModel(message.id)
        assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        assertEquals(1, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `Outbox should mark message as failed after max attempts`() {
        // Given
        val message = createModel().apply {
            message = "invalid json"
            status = OutBoxStatus.PENDING
            delayedUntil = getPastInstant()
            attemptCount = maxAttempts - 1  // One more attempt will exceed max attempts
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        processOutbox()

        // Then
        // Verify message was marked as failed
        val updatedMessage = findModel(message.id)
        assertEquals(OutBoxStatus.FAILED, updatedMessage.status)
        assertEquals(maxAttempts, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `Outbox should handle emitter failure`() {
        // Given
        val workflowMessage = getBasicWorkflowMessage()
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        val message = createModel().apply {
            message = messageJson
            status = OutBoxStatus.PENDING
            delayedUntil = getPastInstant()
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = findModel(message.id)
        assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        assertEquals(1, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `cleanupOutbox should delete old sent messages`() {
        // Given
        val oldMessage = createModel().apply {
            message = "old message"
            status = OutBoxStatus.SENT
            delayedUntil = getPastInstantDays(8)
        }
        val recentMessage = createModel().apply {
            message = "recent message"
            status = OutBoxStatus.SENT
            delayedUntil = getPastInstantDays(1)
        }
        entityManager.persist(oldMessage)
        entityManager.persist(recentMessage)
        entityManager.flush()

        // When
        cleanupOutbox()

        // Then
        // Verify old message was deleted
        val remainingMessages = entityManager
            .createQuery("FROM ${entity.simpleName}", entity)
            .resultList

        assertEquals(
            1,
            remainingMessages.size,
            "Remaining messages is not recentMessage: ${remainingMessages.size}"
        )
        assertEquals(
            recentMessage.id,
            remainingMessages[0].id,
            "Remaining message ID doesn't match"
        )
    }

    @Test
    @Transactional
    fun `cleanupOutbox should not delete non-sent messages`() {
        // Given
        val oldPendingMessage = createModel().apply {
            message = "old pending message"
            status = OutBoxStatus.PENDING
            delayedUntil = getPastInstantDays(8)
        }
        val oldFailedMessage = createModel().apply {
            message = "old failed message"
            status = OutBoxStatus.FAILED
            delayedUntil = getPastInstantDays(8)
        }
        entityManager.persist(oldPendingMessage)
        entityManager.persist(oldFailedMessage)
        entityManager.flush()

        // When
        cleanupOutbox()

        // Then
        // Verify messages were not deleted
        val remainingMessages = entityManager
            .createQuery("FROM ${entity.simpleName}", entity)
            .resultList

        assertEquals(2, remainingMessages.size)
    }

    @Test
    @Transactional
    fun `Outbox should process messages in batches`() {
        // Given
        val workflowMessage = getBasicWorkflowMessage()
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // Create 150 messages (more than typical batch size)
        repeat(150) {
            val message = createModel().apply {
                message = messageJson
                status = OutBoxStatus.PENDING
                delayedUntil = getPastInstant()
                attemptCount = 0
            }
            entityManager.persist(message)
        }
        entityManager.flush()

        // When
        processOutbox()

        // Then
        verify(exactly = 150) { emitter.send(any()) }
    }
}
