package com.lemline.worker.services

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.worker.PostgresTestResource
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.RetryOutbox
import com.lemline.worker.repositories.RetryRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
internal class RetryOutboxTest {

    @Inject
    lateinit var repository: RetryRepository

    @Inject
    lateinit var entityManager: EntityManager

    private val emitter = mockk<Emitter<String>>()

    private lateinit var outbox: RetryOutbox

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM RetryModel").executeUpdate()
        entityManager.flush()

        // Reset mock behavior
        clearMocks(emitter)
        every { emitter.send(any()) } returns CompletableFuture.completedStage(null)

        // Create a new outbox initialPosition with the mock emitter
        outbox = RetryOutbox(
            repository = repository,
            emitter = emitter,
            retryMaxAttempts = 3,
            batchSize = 100,
            cleanupBatchSize = 500,
            cleanupAfterDays = 7,
            retryInitialDelaySeconds = 5
        )
    }

    private fun getWorkflowMessage() = WorkflowMessage.newInstance(
        "test-workflow", "1.0.0", "ID", JsonPrimitive("description")
    )

    @Test
    @Transactional
    fun `RetryOutbox should process pending messages and mark them as sent`() {
        // Given
        val workflowMessage = getWorkflowMessage()
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        val message = RetryModel().apply {
            message = messageJson
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // When
        outbox.processOutbox()

        // Then
        // Verify message was sent
        verify { emitter.send(messageJson) }

        // Verify message status was updated
        val updatedMessage = entityManager.find(RetryModel::class.java, message.id)
        assertEquals(OutBoxStatus.SENT, updatedMessage.status)
        assertEquals(0, updatedMessage.attemptCount)
    }

    @Test
    @Transactional
    fun `RetryOutbox should mark message for retry after processing failure`() {
        // Given
        val message = RetryModel().apply {
            message = "invalid json"
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        outbox.processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = entityManager.find(RetryModel::class.java, message.id)
        assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        assertEquals(1, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `RetryOutbox should mark message as failed after max attempts`() {
        // Given
        val message = RetryModel().apply {
            message = "invalid json"
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 2  // One more attempt will exceed max attempts (3)
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        try {
            outbox.processOutbox()
        } catch (e: RuntimeException) {
            // Expected exception
            assertEquals("Emitter failed", e.message)
        }

        // Then
        // Verify message was marked as failed
        val updatedMessage = entityManager.find(RetryModel::class.java, message.id)
        assertEquals(OutBoxStatus.FAILED, updatedMessage.status)
        assertEquals(3, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `RetryOutbox should handle emitter failure`() {
        // Given
        val workflowMessage = WorkflowMessage(
            "test-workflow", "1.0.0", emptyMap(), NodePosition(listOf())
        )
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        val message = RetryModel().apply {
            message = messageJson
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        every { emitter.send(any()) } throws RuntimeException("Emitter failed")

        // When
        outbox.processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = entityManager.find(RetryModel::class.java, message.id)
        assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        assertEquals(1, updatedMessage.attemptCount)
        assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `cleanupOutbox should delete old sent messages`() {
        // Given
        val oldMessage = RetryModel().apply {
            message = "old message"
            status = OutBoxStatus.SENT
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val recentMessage = RetryModel().apply {
            message = "recent message"
            status = OutBoxStatus.SENT
            delayedUntil = Instant.now().minus(1, ChronoUnit.DAYS)
        }
        entityManager.persist(oldMessage)
        entityManager.persist(recentMessage)
        entityManager.flush()

        // When
        outbox.cleanupOutbox()

        // Then
        // Verify old message was deleted
        val remainingMessages = entityManager
            .createQuery("FROM RetryModel", RetryModel::class.java)
            .resultList

        assertEquals(
            1,
            remainingMessages.size,
            "Remaining messages is not recentMessage:\n${remainingMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
        assertEquals(
            recentMessage.id,
            remainingMessages[0].id,
            "Remaining messages is not recentMessage:\n${remainingMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
    }

    @Test
    @Transactional
    fun `cleanupOutbox should not delete non-sent messages`() {
        // Given
        val oldPendingMessage = RetryModel().apply {
            message = "old pending message"
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val oldFailedMessage = RetryModel().apply {
            message = "old failed message"
            status = OutBoxStatus.FAILED
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val oldSentMessage = RetryModel().apply {
            message = "old sent message"
            status = OutBoxStatus.SENT
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        entityManager.persist(oldPendingMessage)
        entityManager.persist(oldFailedMessage)
        entityManager.persist(oldSentMessage)
        entityManager.flush()

        // When
        outbox.cleanupOutbox()

        // Then
        // Verify messages were not deleted
        val remainingMessages = entityManager
            .createQuery("FROM RetryModel", RetryModel::class.java)
            .resultList

        assertEquals(
            2,
            remainingMessages.size,
            "Remaining messages size is not 2:\n${remainingMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
    }

    @Test
    @Transactional
    fun `RetryOutbox should process messages in batches`() {
        // Given
        val workflowMessage = WorkflowMessage(
            "test-workflow", "1.0.0", emptyMap(), NodePosition(listOf())
        )
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // Create 150 messages (more than default batch size of 100)
        repeat(150) { i ->
            val message = RetryModel().apply {
                message = messageJson
                status = OutBoxStatus.PENDING
                delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
                attemptCount = 0
            }
            entityManager.persist(message)
        }
        entityManager.flush()

        // When
        outbox.processOutbox()

        // Then
        verify(exactly = 150) { emitter.send(any()) }
    }
} 