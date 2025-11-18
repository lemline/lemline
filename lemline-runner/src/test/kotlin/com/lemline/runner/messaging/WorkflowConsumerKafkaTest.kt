// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.WORKFLOWS_IN_CHANNEL
import com.lemline.runner.messaging.commands.WORKFLOWS_OUT_CHANNEL
import com.lemline.runner.messaging.events.DATABASE_IN_CHANNEL
import com.lemline.runner.messaging.events.DATABASE_OUT_CHANNEL
import com.lemline.runner.tests.profiles.KafkaProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag

/**
 * Runs the WorkflowConsumerTest suite against a Kafka broker.
 */
@QuarkusTest
@TestProfile(KafkaProfile::class)
@Tag("integration")
@EnabledOnlyIfDockerAvailable
@ExperimentalTime
@ExperimentalSerializationApi
internal class WorkflowConsumerKafkaTest : WorkflowConsumerTest() {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @ConfigProperty(name = "mp.messaging.incoming.$WORKFLOWS_IN_CHANNEL.topic")
    lateinit var instanceTopicIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.$WORKFLOWS_OUT_CHANNEL.topic")
    lateinit var instanceTopicOut: String

    @ConfigProperty(name = "mp.messaging.incoming.$DATABASE_IN_CHANNEL.topic")
    lateinit var databaseTopicIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.$DATABASE_OUT_CHANNEL.topic")
    lateinit var databaseTopicOut: String

    private lateinit var instanceProducer: KafkaProducer<String, String>
    private lateinit var instanceConsumer: KafkaConsumer<String, String>

    private lateinit var databaseProducer: KafkaProducer<String, String>
    private lateinit var databaseConsumer: KafkaConsumer<String, String>

    override fun setupMessaging() {
        require(instanceTopicIn != instanceTopicOut) { "For *testing*, topics In ($instanceTopicIn) and Out ($instanceTopicOut) must be different" }
        require(databaseTopicIn != databaseTopicOut) { "For *testing*, topics In ($databaseTopicIn) and Out ($databaseTopicOut) must be different" }

        // Setup Kafka producer
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
        )
        instanceProducer = KafkaProducer(producerProps)
        databaseProducer = KafkaProducer(producerProps)

        // Set up Kafka consumer
        val baseConsumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            // Disable auto commit and rely on explicit commit
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false",
        )

        instanceConsumer = KafkaConsumer(baseConsumerProps + (ConsumerConfig.GROUP_ID_CONFIG to "test-group-instance"))
        instanceConsumer.subscribe(listOf(instanceTopicOut))

        databaseConsumer = KafkaConsumer(baseConsumerProps + (ConsumerConfig.GROUP_ID_CONFIG to "test-group-database"))
        databaseConsumer.subscribe(listOf(databaseTopicOut))

        // Flush instance topic by consuming all messages
        var records: ConsumerRecords<String?, String?>?
        do {
            records = instanceConsumer.poll(Duration.ofMillis(100))
        } while (records.count() > 0)
        instanceConsumer.commitSync()
        // Flush database topic by consuming all messages
        do {
            records = databaseConsumer.poll(Duration.ofMillis(100))
        } while (records.count() > 0)
        databaseConsumer.commitSync()
    }

    override fun cleanupMessaging() {
        if (::instanceProducer.isInitialized) instanceProducer.close()
        if (::instanceConsumer.isInitialized) instanceConsumer.close()
        if (::databaseProducer.isInitialized) databaseProducer.close()
        if (::databaseConsumer.isInitialized) databaseConsumer.close()
    }

    override fun sendInstanceMessage(message: String) {
        instanceProducer.send(ProducerRecord(instanceTopicIn, message)).get()
    }

    override fun receiveInstanceMessage(timeout: Long, unit: TimeUnit): String? {
        val records = instanceConsumer.poll(Duration.ofMillis(unit.toMillis(timeout)))
        return records.firstOrNull()?.value()
    }

    override fun receiveDatabaseMessage(timeout: Long, unit: TimeUnit): String? {
        val records = databaseConsumer.poll(Duration.ofMillis(unit.toMillis(timeout)))
        return records.firstOrNull()?.value()
    }
}
