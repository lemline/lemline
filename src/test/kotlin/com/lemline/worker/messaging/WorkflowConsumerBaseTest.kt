package com.lemline.worker.messaging

import com.lemline.common.json.Json
import com.lemline.worker.models.WorkflowDefinition
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.repositories.RetryRepository
import com.lemline.worker.repositories.WaitRepository
import com.lemline.worker.repositories.WorkflowDefinitionRepository
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

abstract class WorkflowConsumerBaseTest {

    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var retryRepository: RetryRepository

    @Inject
    lateinit var waitRepository: WaitRepository

    @Inject
    lateinit var workflowDefinitionRepository: WorkflowDefinitionRepository

    @Inject
    lateinit var workflowConsumer: WorkflowConsumer

    @BeforeEach
    @Transactional
    fun setup() {
        // Clear the database
        entityManager.createQuery("DELETE FROM RetryMessage").executeUpdate()
        entityManager.createQuery("DELETE FROM WaitMessage").executeUpdate()
        entityManager.createQuery("DELETE FROM WorkflowDefinition").executeUpdate()
        entityManager.flush()

        // Create test workflow definition
        val workflowDefinition = WorkflowDefinition().apply {
            name = "test-workflow"
            version = "1.0.0"
            definition = """
            document:
                dsl: '1.0.0'
                namespace: test
                name: test-workflow
                version: '1.0.0'
            do:
                - test:
                    switch:
                      - task:
                          when: @{ . == "task" }
                          then: taskCase
                      - wait:
                          when: @{ . == "wait" }
                          then: waitCase
                      - completed:
                          when: @{ . == "completed" }
                          then: exit
                      - error:
                          when: @{ . =&= "error" }
                          then: exit
                - taskCase:
                    call: http
                    with:
                      method: get
                      endpoint: https://swapi.dev/api/people
                    then: exit
                - waitCase:
                    wait:
                      seconds: 30
                    then: exit
            """.trimIndent().replace("@", "$")
        }
        with(workflowDefinitionRepository) { workflowDefinition.save() }
        entityManager.flush()

        setupMessaging()
    }

    protected abstract fun setupMessaging()

    @AfterEach
    fun cleanup() {
        cleanupMessaging()
    }

    protected abstract fun cleanupMessaging()

    protected abstract fun sendMessage(message: String)

    protected abstract fun receiveMessage(timeout: Long, unit: TimeUnit): String?

    @Test
    fun `should process valid workflow message and send to output topic`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            name = "test-workflow",
            version = "1.0.0",
            id = "test-id",
            input = JsonPrimitive("task")
        )
        val messageJson = Json.toJson(workflowMessage)

        // When
        sendMessage(messageJson)

        // Then
        // Wait for message to be processed
        println("output = ${workflowConsumer.waitForProcessing(messageJson).get()}")
        val outputMessage = receiveMessage(5, TimeUnit.SECONDS)
        assertNotNull(outputMessage, "No messages received from output topic")
    }

    @Test
    fun `should store invalid message in retry repository`() {
        // Given
        val invalidMessage = "invalid json message"

        // When
        sendMessage(invalidMessage)

        // Wait for message to be processed
        workflowConsumer.waitForProcessing(invalidMessage).get()

        // Verify message was stored in retry repository
        val retryMessages = retryRepository.listAll()

        assertTrue(retryMessages.isNotEmpty(), "No messages found in retry repository")
        assertEquals(invalidMessage, retryMessages[0].message, "Retry message doesn't match input message")
        assertEquals(OutBoxStatus.PENDING, retryMessages[0].status, "Retry message status is not PENDING")
        assertEquals(0, retryMessages[0].attemptCount, "Retry message attempt count is not 0")
    }

    @Test
    fun `should store instance with error in retry repository`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            "test-workflow",
            "1.0.0",
            "test-id",
            JsonPrimitive("error")
        )
        val messageJson = Json.toJson(workflowMessage)

        // When
        sendMessage(messageJson)

        // Wait for message to be processed
        workflowConsumer.waitForProcessing(messageJson).get()

        // Verify message was stored in retry repository
        val retryMessages = retryRepository.listAll()

        assertTrue(retryMessages.isNotEmpty(), "No messages found in retry repository")
        assertEquals(messageJson, retryMessages[0].message, "Retry message doesn't match input message")
        assertEquals(OutBoxStatus.PENDING, retryMessages[0].status, "Retry message status is not PENDING")
        assertEquals(0, retryMessages[0].attemptCount, "Retry message attempt count is not 0")
    }

    @Test
    fun `should store waiting workflow in wait repository`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            "test-workflow",
            "1.0.0",
            "test-id",
            JsonPrimitive("wait")
        )
        val messageJson = Json.toJson(workflowMessage)

        // When
        sendMessage(messageJson)

        // Then
        // Wait for message to be processed
        workflowConsumer.waitForProcessing(messageJson).get()

        // Verify message was stored in wait repository
        val waitMessages = waitRepository.listAll()

        assertTrue(waitMessages.isNotEmpty(), "No messages found in wait repository")
        assertEquals(OutBoxStatus.PENDING, waitMessages[0].status, "Wait message status is not PENDING")
        assertEquals(0, waitMessages[0].attemptCount, "Wait message attempt count is not 0")

        // Verify delay was set correctly (within 1 second of expected)
        val expectedDelay = Instant.now().plus(30, ChronoUnit.SECONDS)
        val actualDelay = waitMessages[0].delayedUntil
        assertTrue(
            actualDelay.isAfter(expectedDelay.minus(1, ChronoUnit.SECONDS)) &&
                    actualDelay.isBefore(expectedDelay.plus(1, ChronoUnit.SECONDS)),
            "Wait message delay is not set correctly"
        )
    }

    @Test
    fun `should handle completed workflow without sending message`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            "test-workflow",
            "1.0.0",
            "test-id",
            JsonPrimitive("completed")
        )
        val messageJson = Json.toJson(workflowMessage)

        // When
        sendMessage(messageJson)

        // Then
        // Wait for message to be processed
        workflowConsumer.waitForProcessing(messageJson).get()
        val outputMessage = receiveMessage(1, TimeUnit.SECONDS)
        assertTrue(outputMessage == null, "Messages were sent to output topic: $outputMessage")

        // Verify no messages were stored in repositories
        val retryMessages = retryRepository.listAll()
        assertEquals(
            0,
            retryMessages.size,
            "Messages were stored in retry repository: ${retryMessages.map { Json.toPrettyJson(it) }}"
        )

        val waitMessages = waitRepository.listAll()
        assertEquals(
            0,
            waitMessages.size,
            "Messages were stored in wait repository:  ${waitMessages.map { Json.toPrettyJson(it) }}"
        )
    }
} 