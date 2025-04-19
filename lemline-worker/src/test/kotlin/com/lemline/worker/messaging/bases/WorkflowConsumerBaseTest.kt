package com.lemline.worker.messaging.bases

import com.lemline.core.json.LemlineJson
import com.lemline.worker.messaging.WorkflowConsumer
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.models.WorkflowModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.repositories.RetryRepository
import com.lemline.worker.repositories.WaitRepository
import com.lemline.worker.repositories.WorkflowRepository
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
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
    lateinit var workflowRepository: WorkflowRepository

    @Inject
    lateinit var workflowConsumer: WorkflowConsumer

    @BeforeEach
    @Transactional
    fun setup() {
        // Clear the database
        entityManager.createQuery("DELETE FROM RetryModel").executeUpdate()
        entityManager.createQuery("DELETE FROM WaitModel").executeUpdate()
        entityManager.createQuery("DELETE FROM WorkflowModel").executeUpdate()
        entityManager.flush()

        // Create test workflow definition
        val workflowModel = WorkflowModel().apply {
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
                          when: @{ . == "retry" }
                          then: retryCase
                - taskCase:
                    call: http
                    with:
                      method: get
                      endpoint: https://jsonplaceholder.typicode.com/posts/1
                    then: exit
                - waitCase:
                    wait:
                      seconds: 30
                    then: exit
                - retryCase:
                    try:
                      - raiseError:
                          raise:
                            error:
                              type: https://serverlessworkflow.io/errors/not-implemented
                              status: 500
                    catch:
                      retry:
                        limit:
                           attempt:
                            count: 2
                        delay: PT1S
                      do:
                        - setCaught:
                            set:
                              caught: true
            """.trimIndent().replace("@", "$")
        }
        with(workflowRepository) { workflowModel.save() }
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

    private fun sendMessageFuture(messageJson: String): CompletableFuture<String?> {
        val future = workflowConsumer.waitForProcessing(messageJson)
        // Send the message to the input topic
        sendMessage(messageJson)
        return future
    }

    @Test
    fun `should process valid workflow message and send to output topic`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            name = "test-workflow",
            version = "1.0.0",
            id = "test-id",
            input = JsonPrimitive("task")
        )
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // When
        val future = sendMessageFuture(messageJson)

        // Then
        // Wait for message to be processed
        println("output = ${future.get()}")
        val outputMessage = receiveMessage(5, TimeUnit.SECONDS)
        assertNotNull(outputMessage, "No messages received from output topic")

        // Verify no message were stored in repositories
        val retryMessages = retryRepository.listAll()
        val waitMessages = waitRepository.listAll()
        assertTrue(retryMessages.isEmpty(), "Messages found in retry repository")
        assertTrue(
            waitMessages.isEmpty(),
            "Messages found in wait repository:\n${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
    }

    @Test
    fun `invalid message should be stored in retry table as Failed`() {
        // Given
        val invalidMessage = "invalid json message"

        // When
        val future = sendMessageFuture(invalidMessage)

        // This message can not be processed
        shouldThrowAny { future.get() }

        val retryMessages = retryRepository.listAll()
        retryMessages[0].message shouldBe invalidMessage
        retryMessages[0].status shouldBe OutBoxStatus.FAILED

        // Verify no message were stored in wait repository
        val waitMessages = waitRepository.listAll()
        assertTrue(
            waitMessages.isEmpty(),
            "Messages found in wait repository:\n${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
    }

    @Test
    fun `should store instance with retry in retry repository`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            "test-workflow",
            "1.0.0",
            "test-id",
            JsonPrimitive("retry")
        )
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // When
        val future = sendMessageFuture(messageJson)

        // Wait for message to be processed
        future.get()

        // Verify message was stored in retry repository
        val retryMessages = retryRepository.listAll()

        assertTrue(retryMessages.isNotEmpty(), "No messages found in retry repository")
        assertEquals(OutBoxStatus.PENDING, retryMessages[0].status, "Retry message status is not PENDING")
        assertEquals(0, retryMessages[0].attemptCount, "Retry message attempt count is not 0")
    }

    @Test
    fun `should store waiting instance in wait repository`() {
        // Given
        val workflowMessage = WorkflowMessage.newInstance(
            "test-workflow",
            "1.0.0",
            "test-id",
            JsonPrimitive("wait")
        )
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // When
        val future = sendMessageFuture(messageJson)

        // Then
        // Wait for message to be processed
        future.get()

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
        val messageJson = LemlineJson.encodeToString(workflowMessage)

        // When
        val future = sendMessageFuture(messageJson)

        // Then
        // Wait for message to be processed
        future.get()
        val outputMessage = receiveMessage(1, TimeUnit.SECONDS)
        assertTrue(outputMessage == null, "Messages were sent to output topic: $outputMessage")

        // Verify no messages were stored in repositories
        val retryMessages = retryRepository.listAll()
        assertEquals(
            0,
            retryMessages.size,
            "Messages were stored in retry repository: ${retryMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )

        val waitMessages = waitRepository.listAll()
        assertEquals(
            0,
            waitMessages.size,
            "Messages were stored in wait repository:  ${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}"
        )
    }
} 