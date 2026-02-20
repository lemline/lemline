// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.tests.profiles.KafkaProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowConsumerTest suite against a Kafka broker.
 */
@QuarkusTest
@TestProfile(KafkaProfile::class)
@Tag("integration")
@RequiresDocker
@ExperimentalTime
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerKafkaTest : WorkflowConsumerTest() {

    private lateinit var bootstrapServers: String
    private lateinit var commandsTopicIn: String
    private lateinit var commandsTopicOut: String
    private lateinit var eventsTopicIn: String
    private lateinit var eventsTopicOut: String

    private lateinit var commandsProducer: KafkaProducer<String, String>
    private lateinit var commandsConsumer: KafkaConsumer<String, String>
    private lateinit var eventsProducer: KafkaProducer<String, String>
    private lateinit var eventsConsumer: KafkaConsumer<String, String>

    @BeforeAll
    fun initClients() {
        val config = ConfigProvider.getConfig()

        bootstrapServers = config.getValue("kafka.bootstrap.servers", String::class.java)
        commandsTopicIn = config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.topic", String::class.java)
        commandsTopicOut = config.getValue("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.topic", String::class.java)
        eventsTopicIn = config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.topic", String::class.java)
        eventsTopicOut = config.getValue("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.topic", String::class.java)

        require(commandsTopicIn != commandsTopicOut) {
            "For *testing*, topics In ($commandsTopicIn) and Out ($commandsTopicOut) must be different"
        }
        require(eventsTopicIn != eventsTopicOut) {
            "For *testing*, topics In ($eventsTopicIn) and Out ($eventsTopicOut) must be different"
        }

        // Setup Kafka producer
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
        )
        commandsProducer = KafkaProducer(producerProps)
        eventsProducer = KafkaProducer(producerProps)

        // Set up Kafka consumer with unique group IDs per test run to avoid offset issues
        val baseConsumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            // Disable auto commit and rely on explicit commit
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false",
        )

        // Use unique group IDs to avoid offset conflicts
        val uniqueSuffix = System.currentTimeMillis()
        commandsConsumer =
            KafkaConsumer(baseConsumerProps + (ConsumerConfig.GROUP_ID_CONFIG to "test-commands-$uniqueSuffix"))
        commandsConsumer.subscribe(listOf(commandsTopicOut))

        eventsConsumer =
            KafkaConsumer(baseConsumerProps + (ConsumerConfig.GROUP_ID_CONFIG to "test-events-$uniqueSuffix"))
        eventsConsumer.subscribe(listOf(eventsTopicOut))

        // Initial poll to trigger partition assignment
        commandsConsumer.poll(Duration.ofMillis(100))
        eventsConsumer.poll(Duration.ofMillis(100))
    }

    @AfterAll
    fun closeClients() {
        if (::commandsProducer.isInitialized) commandsProducer.close()
        if (::commandsConsumer.isInitialized) commandsConsumer.close()
        if (::eventsProducer.isInitialized) eventsProducer.close()
        if (::eventsConsumer.isInitialized) eventsConsumer.close()
    }

    override fun setupMessaging() {
        // Wait and drain again to catch any stragglers
        Thread.sleep(100)
        drainMessages(commandsConsumer)
        drainMessages(eventsConsumer)
    }

    private fun drainMessages(consumer: KafkaConsumer<String, String>) {
        var records = consumer.poll(Duration.ofMillis(100))
        while (records.count() > 0) {
            records = consumer.poll(Duration.ofMillis(100))
        }
    }

    override fun cleanupMessaging() {
        // No-op - clients are closed in @AfterAll
    }

    override fun sendCommand(message: String) {
        commandsProducer.send(ProducerRecord(commandsTopicIn, message)).get()
    }

    override suspend fun receiveCommand(timeout: Long, unit: TimeUnit): String? =
        commandsConsumer.poll(Duration.ofMillis(unit.toMillis(timeout))).firstOrNull()?.value()

    override suspend fun receiveEvent(timeout: Long, unit: TimeUnit): String? =
        eventsConsumer.poll(Duration.ofMillis(unit.toMillis(timeout))).firstOrNull()?.value()
}
