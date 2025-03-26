package com.lemline.swruntime.services

import com.lemline.swruntime.PostgresTestResource
import com.lemline.swruntime.json.Json
import com.lemline.swruntime.messaging.WorkflowMessage
import com.lemline.swruntime.models.WAIT_TABLE
import com.lemline.swruntime.models.WaitMessage
import com.lemline.swruntime.models.WaitMessage.MessageStatus
import com.lemline.swruntime.repositories.WaitMessageRepository
import com.lemline.swruntime.sw.tasks.JsonPointer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.*
import org.mockito.kotlin.*
import java.time.Instant
import java.time.temporal.ChronoUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class WaitMessageOutboxTest {

    @Inject
    lateinit var repository: WaitMessageRepository

    @Inject
    lateinit var entityManager: EntityManager

    private lateinit var emitter: Emitter<String>
    private lateinit var outbox: WaitMessageOutbox

    @BeforeEach
    @Transactional
    fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM $WAIT_TABLE").executeUpdate()

        // Create a fresh mock emitter for each test
        emitter = mock()

        // Create a new outbox instance with the mock emitter
        outbox = WaitMessageOutbox(
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
    fun `processOutbox should process pending messages and mark them as sent`() {
        // Given
        val workflowMessage = getWorkflowMessage()
        val messageJson = Json.toJson(workflowMessage)

        val message = WaitMessage().apply {
            message = messageJson
            status = WaitMessage.MessageStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // When
        outbox.processOutbox()

        // Then
        // Verify message was sent
        verify(emitter).send(argThat { this == messageJson })

        // Verify message status was updated
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(MessageStatus.SENT, updatedMessage.status)
        Assertions.assertEquals(0, updatedMessage.attemptCount)
    }

    @Test
    @Transactional
    fun `processOutbox should mark message for retry after processing failure`() {
        // Given
        val message = WaitMessage().apply {
            message = "invalid json"
            status = MessageStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        whenever(emitter.send(any())).thenThrow(RuntimeException("Emitter failed"))

        // When
        outbox.processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(MessageStatus.PENDING, updatedMessage.status)
        Assertions.assertEquals(1, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `processOutbox should mark message as failed after max attempts`() {
        // Given
        val message = WaitMessage().apply {
            message = "invalid json"
            status = MessageStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 2  // One more attempt will exceed max attempts (3)
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        whenever(emitter.send(any())).thenThrow(RuntimeException("Emitter failed"))

        // When
        outbox.processOutbox()

        // Then
        // Verify message was marked as failed
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(MessageStatus.FAILED, updatedMessage.status)
        Assertions.assertEquals(3, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `processOutbox should handle emitter failure`() {
        // Given
        val workflowMessage = WorkflowMessage("test-workflow", "1.0.0", emptyMap(), JsonPointer(""))
        val messageJson = Json.toJson(workflowMessage)

        val message = WaitMessage().apply {
            message = messageJson
            status = MessageStatus.PENDING
            delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
            attemptCount = 0
        }
        entityManager.persist(message)
        entityManager.flush()

        // Mock emitter to throw exception
        whenever(emitter.send(any())).thenThrow(RuntimeException("Emitter failed"))

        // When
        outbox.processOutbox()

        // Then
        // Verify message status and attempt count were updated
        val updatedMessage = entityManager.find(WaitMessage::class.java, message.id)
        Assertions.assertEquals(MessageStatus.PENDING, updatedMessage.status)
        Assertions.assertEquals(1, updatedMessage.attemptCount)
        Assertions.assertEquals("Emitter failed", updatedMessage.lastError)
    }

    @Test
    @Transactional
    fun `cleanupOutbox should delete old sent messages`() {
        // Given
        val oldMessage = WaitMessage().apply {
            message = "old message"
            status = MessageStatus.SENT
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val recentMessage = WaitMessage().apply {
            message = "recent message"
            status = MessageStatus.SENT
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
            .createQuery("FROM $WAIT_TABLE", WaitMessage::class.java)
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
            status = MessageStatus.PENDING
            delayedUntil = Instant.now().minus(8, ChronoUnit.DAYS)
        }
        val oldFailedMessage = WaitMessage().apply {
            message = "old failed message"
            status = MessageStatus.FAILED
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
            .createQuery("FROM $WAIT_TABLE", WaitMessage::class.java)
            .resultList

        Assertions.assertEquals(2, remainingMessages.size)
    }

    @Test
    @Transactional
    fun `processOutbox should process messages in batches`() {
        // Given
        val workflowMessage = WorkflowMessage("test-workflow", "1.0.0", emptyMap(), JsonPointer(""))
        val messageJson = Json.toJson(workflowMessage)

        // Create 150 messages (more than default batch size of 100)
        repeat(150) {
            val message = WaitMessage().apply {
                message = messageJson
                status = MessageStatus.PENDING
                delayedUntil = Instant.now().minus(1, ChronoUnit.MINUTES)
                attemptCount = 0
            }
            entityManager.persist(message)
        }
        entityManager.flush()

        // When
        outbox.processOutbox()

        // Then
        // Verify all messages were processed
        val processedMessages = entityManager
            .createQuery("FROM $WAIT_TABLE WHERE status = :status", WaitMessage::class.java)
            .setParameter("status", MessageStatus.SENT)
            .resultList

        Assertions.assertEquals(150, processedMessages.size)
        verify(emitter, times(150)).send(any())
    }
} 