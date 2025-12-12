// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import io.cloudevents.kafka.CloudEventDeserializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import kotlin.time.Duration.Companion.milliseconds

/**
 * Kafka-based CloudEvent capture implementation.
 *
 * Subscribes to Kafka topic and captures CloudEvents for registered workflows.
 */
class KafkaCloudEventCapture(
    private val bootstrapServers: String,
    private val topic: String,
    private val dispatcher: CloudEventDispatcher = CloudEventDispatcher.getInstance()
) : AutoCloseable {

    private val logger = logger()
    private var consumerJob: Job? = null
    private var consumer: KafkaConsumer<String, CloudEvent>? = null

    /**
     * Start consuming events from Kafka.
     */
    suspend fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (consumerJob != null) {
            logger.warn { "Kafka capture already started" }
            return
        }

        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "lemline-test-capture-${System.currentTimeMillis()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, CloudEventDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
        }

        consumer = KafkaConsumer(props)
        consumer?.subscribe(listOf(topic))

        consumerJob = scope.launch(Dispatchers.IO) {
            logger.info { "Started Kafka capture on topic: $topic" }

            while (isActive) {
                try {
                    val records = consumer?.poll(Duration.ofMillis(100)) ?: continue

                    for (record in records) {
                        val event = record.value()
                        if (event != null) {
                            dispatcher.dispatch(event)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        logger.error(e) { "Error consuming from Kafka" }
                        delay(100.milliseconds)
                    }
                }
            }
        }
    }

    /**
     * Stop consuming events.
     */
    suspend fun stop() {
        consumerJob?.cancelAndJoin()
        consumerJob = null

        withContext(Dispatchers.IO) {
            consumer?.close()
            consumer = null
        }

        logger.info { "Stopped Kafka capture" }
    }

    override fun close() {
        consumer?.close()
        consumer = null
    }
}
