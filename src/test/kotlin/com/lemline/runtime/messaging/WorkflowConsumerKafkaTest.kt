package com.lemline.runtime.messaging

import com.lemline.runtime.KafkaTestProfile
import com.lemline.runtime.KafkaTestResource
import com.lemline.runtime.PostgresTestResource
import com.lemline.runtime.json.Json
import com.lemline.runtime.models.WorkflowDefinition
import com.lemline.runtime.outbox.OutBoxStatus
import com.lemline.runtime.repositories.RetryRepository
import com.lemline.runtime.repositories.WaitRepository
import com.lemline.runtime.repositories.WorkflowDefinitionRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import kotlinx.serialization.json.JsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
@QuarkusTestResource(KafkaTestResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(KafkaTestProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
internal class WorkflowConsumerKafkaTest {

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

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    private val inputTopic = "workflows"
    private val outputTopic = "workflows"

    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var consumer: KafkaConsumer<String, String>

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

        // Setup Kafka producer
        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
        producer = KafkaProducer(producerProps)

        // Setup Kafka consumer
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-group")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        }
        consumer = KafkaConsumer(consumerProps)
        consumer.subscribe(listOf(outputTopic))
    }

    @AfterEach
    fun cleanup() {
        producer.close()
        consumer.close()
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
        val messageJson = Json.toJson(workflowMessage)

        // When
        producer.send(ProducerRecord(inputTopic, messageJson)).get()

        // Then
        // Wait for message to be processed
        val records = consumer.poll(Duration.ofSeconds(5))
        assertTrue(records.count() > 0, "No messages received from output topic")
        val outputMessage = records.first().value()
        assertNotNull(outputMessage, "Output message is null")
    }

    @Test
    fun `should store invalid message in retry repository`() {
        // Given
        val invalidMessage = "invalid json message"

        // When
        producer.send(ProducerRecord(inputTopic, invalidMessage)).get()

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
        producer.send(ProducerRecord(inputTopic, messageJson)).get()

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
        producer.send(ProducerRecord(inputTopic, messageJson)).get()

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
        producer.send(ProducerRecord(inputTopic, messageJson)).get()

        // Then
        // Wait for message to be processed
        workflowConsumer.waitForProcessing(messageJson).get()
        val records = consumer.poll(Duration.ofSeconds(1))
        assertEquals(0, records.count(), "Messages were sent to output topic")

        // Verify no messages were stored in repositories
        val retryMessages = retryRepository.listAll()
        assertEquals(0, retryMessages.size, "Messages were stored in retry repository")

        val waitMessages = waitRepository.listAll()
        assertEquals(0, waitMessages.size, "Messages were stored in wait repository")
    }
} 