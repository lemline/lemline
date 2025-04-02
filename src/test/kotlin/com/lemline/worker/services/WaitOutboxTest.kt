package com.lemline.worker.services

import com.lemline.common.json.Json
import com.lemline.worker.PostgresTestResource
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.models.WaitMessage
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.WaitOutbox
import com.lemline.worker.repositories.WaitRepository
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
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
internal class WaitOutboxTest {

    @Inject
    lateinit var repository: WaitRepository

    @Inject
    lateinit var entityManager: EntityManager

    private val emitter = mockk<Emitter<String>>()

    private lateinit var outbox: WaitOutbox

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM WaitMessage").executeUpdate()
        entityManager.flush()

        // Reset mock behavior
        clearMocks(emitter)
        every { emitter.send(any()) } returns CompletableFuture.completedStage(null)

        // Create a new outbox instance with the mocks
        outbox = WaitOutbox(
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
    fun `WaitOutbox should process pending messages and mark them as sent`() {
        // Given
        val workflowMessage = getWorkflowMessage()
        val messageJson = Json.toJson(workflowMessage)

        val message = WaitMessage().apply {
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
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(OutBoxStatus.SENT, updatedMessage.status)
        Assertions.assertEquals(0, updatedMessage.attemptCount)
    }

    @Test
    @Transactional
    fun `WaitOutbox should mark message for retry after processing failure`() {
        // Given
        val message = WaitMessage().apply {
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
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        Assertions.assertEquals(1, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `WaitOutbox should mark message as failed after max attempts`() {
        // Given
        val message = WaitMessage().apply {
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
            Assertions.assertEquals("Emitter failed", e.message)
        }

        // Then
        // Verify message was marked as failed
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(OutBoxStatus.FAILED, updatedMessage.status)
        Assertions.assertEquals(3, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `WaitOutbox should handle emitter failure`() {
        // Given
        val workflowMessage = WorkflowMessage(
            "test-workflow", "1.0.0", emptyMap(),
            com.lemline.sw.tasks.JsonPointer("")
        )
        val messageJson = Json.toJson(workflowMessage)

        val message = WaitMessage().apply {
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
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(OutBoxStatus.PENDING, updatedMessage.status)
        Assertions.assertEquals(1, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `cleanupOutbox should delete old sent messages`() {
        // Given
        val oldMessage = WaitMessage().apply {
            message = "old message"
            status = OutBoxStatus.SENT
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val recentMessage = WaitMessage().apply {
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
            .createQuery("FROM WaitMessage", WaitMessage::class.java)
            .resultList

        Assertions.assertEquals(1, remainingMessages.size)
        Assertions.assertEquals(recentMessage.id, remainingMessages[0].id)
    }

    @Test
    @Transactional
    fun `cleanupOutbox should not delete non-sent messages`() {
        // Given
        val oldPendingMessage = WaitMessage().apply {
            message = "old pending message"
            status = OutBoxStatus.PENDING
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val oldFailedMessage = WaitMessage().apply {
            message = "old failed message"
            status = OutBoxStatus.FAILED
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        entityManager.persist(oldPendingMessage)
        entityManager.persist(oldFailedMessage)
        entityManager.flush()

        // When
        outbox.cleanupOutbox()

        // Then
        // Verify messages were not deleted
        val remainingMessages = entityManager
            .createQuery("FROM WaitMessage", WaitMessage::class.java)
            .resultList

        Assertions.assertEquals(2, remainingMessages.size)
    }

    @Test
    @Transactional
    fun `WaitOutbox should process messages in batches`() {
        // Given
        val workflowMessage = WorkflowMessage(
            "test-workflow", "1.0.0", emptyMap(),
            com.lemline.sw.tasks.JsonPointer("")
        )
        val messageJson = Json.toJson(workflowMessage)

        // Create 150 messages (more than default batch size of 100)
        repeat(150) { i ->
            val message = WaitMessage().apply {
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