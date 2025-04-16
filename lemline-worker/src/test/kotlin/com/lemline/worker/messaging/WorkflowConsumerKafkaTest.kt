package com.lemline.worker.messaging

import com.lemline.worker.messaging.bases.WorkflowConsumerBaseTest
import com.lemline.worker.tests.profiles.KafkaTestProfile
import com.lemline.worker.tests.resources.KafkaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag
import java.time.Duration
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(KafkaTestResource::class)
@TestProfile(KafkaTestProfile::class)
@Tag("integration")
internal class WorkflowConsumerKafkaTest : WorkflowConsumerBaseTest() {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @ConfigProperty(name = "mp.messaging.incoming.workflows-in.topic")
    lateinit var topicIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.workflows-out.topic")
    lateinit var topicOut: String

    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var consumer: KafkaConsumer<String, String>

    override fun setupMessaging() {
        require(topicIn != topicOut) { "For *testing*, topics In ($topicIn) and Out ($topicOut) must be different" }

        // Setup Kafka producer
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name
        )
        producer = KafkaProducer(producerProps)

        // Setup Kafka consumer
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-group",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            // Disable auto commit and rely on explicit commit
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false"
        )
        consumer = KafkaConsumer(consumerProps)
        consumer.subscribe(listOf(topicOut))

        // Flush topics by consuming all messages
        var records = consumer.poll(Duration.ofMillis(100))
        while (records.count() > 0) {
            records = consumer.poll(Duration.ofMillis(100))
        }
        consumer.commitSync()
    }

    override fun cleanupMessaging() {
        producer.close()
        consumer.close()
    }

    override fun sendMessage(message: String) {
        producer.send(ProducerRecord(topicIn, message)).get()
    }

    override fun receiveMessage(timeout: Long, unit: TimeUnit): String? {
        val records = consumer.poll(Duration.ofMillis(unit.toMillis(timeout)))
        return records.firstOrNull()?.value()
    }
} 