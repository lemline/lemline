package com.lemline.runtime.messaging

import com.lemline.runtime.PostgresTestResource
import com.lemline.runtime.RabbitMQTestProfile
import com.lemline.runtime.RabbitMQTestResource
import com.lemline.runtime.json.Json
import com.lemline.runtime.models.WorkflowDefinition
import com.lemline.runtime.outbox.OutBoxStatus
import com.lemline.runtime.repositories.RetryRepository
import com.lemline.runtime.repositories.WaitRepository
import com.lemline.runtime.repositories.WorkflowDefinitionRepository
import com.rabbitmq.client.*
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
@QuarkusTestResource(RabbitMQTestResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(RabbitMQTestProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
internal class WorkflowConsumerRabbitMQTest {

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

    @ConfigProperty(name = "rabbitmq-host")
    lateinit var rabbitmqHost: String

    @ConfigProperty(name = "rabbitmq-port")
    lateinit var rabbitmqPort: String

    @ConfigProperty(name = "rabbitmq-username")
    lateinit var rabbitmqUsername: String

    @ConfigProperty(name = "rabbitmq-password")
    lateinit var rabbitmqPassword: String

    @ConfigProperty(name = "mp.messaging.incoming.workflows-in.queue.name")
    lateinit var queueIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.workflows-out.queue.name")
    lateinit var queueOut: String

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private val deliveries: BlockingQueue<Delivery> = LinkedBlockingQueue()

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

        // Setup RabbitMQ connection
        val factory = ConnectionFactory().apply {
            host = rabbitmqHost
            port = rabbitmqPort.toInt()
            username = rabbitmqUsername
            password = rabbitmqPassword
        }
        connection = factory.newConnection()
        channel = connection.createChannel()

        // Declare queue
        require(queueIn == queueOut) { "Queues In and Out must be equal: $queueIn != $queueOut" }
        channel.queueDeclare(queueIn, true, false, false, null)

        // Setup consumer
        val deliverCallback = DeliverCallback { _, delivery -> deliveries.offer(delivery) }
        channel.basicConsume(queueOut, true, deliverCallback, CancelCallback { })
    }

    @AfterEach
    fun cleanup() {
        channel.close()
        connection.close()
    }

    private fun sendMessage(msg: String) {
        channel.basicPublish("", queueIn, MessageProperties.PERSISTENT_TEXT_PLAIN, msg.toByteArray())
    }

    private fun receiveMessage(timeout: Long, unit: TimeUnit) =
        deliveries.poll(timeout, unit)?.let { String(it.body) }

    @Test
    fun `should process valid workflow message and send to output queue`() {
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
        val outputMessage = receiveMessage(5, TimeUnit.SECONDS)
        assertNotNull(outputMessage, "No messages received from output queue")
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
        val delivery = deliveries.poll(1, TimeUnit.SECONDS)
        assertTrue(delivery == null, "Messages were sent to output queue")

        // Verify no messages were stored in repositories
        val retryMessages = retryRepository.listAll()
        assertEquals(0, retryMessages.size, "Messages were stored in retry repository")

        val waitMessages = waitRepository.listAll()
        assertEquals(0, waitMessages.size, "Messages were stored in wait repository")
    }
} 