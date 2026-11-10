// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.kafka

import com.lemline.common.logger.logger
import io.opentelemetry.api.OpenTelemetry
import io.smallrye.common.annotation.Identifier
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor
import io.smallrye.reactive.messaging.ClientCustomizer
import io.smallrye.reactive.messaging.SubscriberDecorator
import io.smallrye.reactive.messaging.kafka.DeserializationFailureHandler
import io.smallrye.reactive.messaging.kafka.IncomingKafkaRecord
import io.smallrye.reactive.messaging.kafka.KafkaCDIEvents
import io.smallrye.reactive.messaging.kafka.KafkaConnector
import io.smallrye.reactive.messaging.kafka.KafkaConnectorIncomingConfiguration
import io.smallrye.reactive.messaging.kafka.KafkaConnectorOutgoingConfiguration
import io.smallrye.reactive.messaging.kafka.KafkaConsumer
import io.smallrye.reactive.messaging.kafka.SerializationFailureHandler
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.kafka.fault.KafkaDeadLetterQueue
import io.smallrye.reactive.messaging.kafka.fault.KafkaFailureHandler
import io.smallrye.reactive.messaging.kafka.impl.ConfigHelper
import io.smallrye.reactive.messaging.kafka.impl.KafkaSink
import io.smallrye.reactive.messaging.providers.impl.OverrideConnectorConfig
import io.smallrye.reactive.messaging.providers.wiring.Wiring.wireOutgoingConnectorToUpstream
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.BiConsumer
import java.util.function.Function
import org.apache.kafka.clients.CommonClientConfigs.CLIENT_ID_CONFIG
import org.apache.kafka.clients.consumer.ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG
import org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.reactive.messaging.spi.ConnectorFactory.INCOMING_PREFIX

/**
 * Custom Kafka failure strategy that dead-letters each failed record to a topic derived from
 * the record's **source** topic: `<record.topic()>.dlq`.
 *
 * Rationale: Lemline channels subscribe to multiple topics at once (one per workflow namespace,
 * e.g. `lemline-acme-commands,lemline-team-commands`). The stock `dead-letter-queue` strategy
 * supports only one static `dead-letter-queue.topic` per channel, which would mix namespaces in a
 * single DLQ. This handler preserves the per-namespace topic convention instead.
 *
 * Behavior mirrors the stock [KafkaDeadLetterQueue] (SmallRye Reactive Messaging Kafka):
 * - same diagnostic headers (`dead-letter-reason`, `dead-letter-cause`, original
 *   topic/partition/offset, exception class names),
 * - same producer derivation from the channel/consumer configuration (serializers mirrored from
 *   the channel's deserializers, `dead-letter-queue.producer-client-id` honored, channel-level
 *   `dead-letter-queue.*` nested attributes still applied),
 * - same ack/nack semantics (the failed record is acked only once the DLQ write succeeds).
 *
 * The only intended difference: the target topic is computed per record and is not configurable.
 * An explicit topic set through [OutgoingKafkaRecordMetadata] on the nack metadata still wins,
 * as in the stock implementation.
 *
 * Channel usage: `mp.messaging.incoming.<channel>.failure-strategy=lemline-namespace-dlq`.
 *
 * Note: this class implements the SmallRye [KafkaFailureHandler] SPI, which is Mutiny-based
 * ([Uni]); the "no Mutiny in application code" rule does not apply to this SPI boundary.
 */
class NamespaceDeadLetterQueue(
    private val channel: String,
    private val dlqSink: KafkaSink,
    private val dlqSource: UnicastProcessor<Message<*>>,
) : KafkaFailureHandler {

    companion object {
        /** Value to use in `failure-strategy` channel configuration. */
        const val STRATEGY = "lemline-namespace-dlq"

        /** Suffix appended to the source topic to derive the dead-letter topic. */
        const val DLQ_TOPIC_SUFFIX = ".dlq"

        /** Derives the dead-letter topic for a record consumed from [sourceTopic]. */
        fun deadLetterTopicFor(sourceTopic: String): String = "$sourceTopic$DLQ_TOPIC_SUFFIX"

        /**
         * Maps the channel's configured deserializer to the matching serializer, as the stock DLQ
         * does (e.g. `StringDeserializer` -> `StringSerializer`). Defaults to [StringSerializer].
         */
        internal fun mirrorSerializer(deserializer: String?): String =
            deserializer?.replace("Deserializer", "Serializer") ?: StringSerializer::class.java.name

        internal fun throwableMessage(throwable: Throwable): String =
            throwable.message ?: throwable.toString()

        /**
         * Builds the dead-letter [ProducerRecord] for a failed record, targeting
         * `<record.topic()>.dlq` (unless overridden by [outgoing] metadata) and carrying the same
         * diagnostic headers as the stock [KafkaDeadLetterQueue].
         */
        internal fun <K, V> createDeadLetterRecord(
            record: IncomingKafkaRecord<K, V>,
            reason: Throwable,
            outgoing: OutgoingKafkaRecordMetadata<K>?,
        ): ProducerRecord<K, V> {
            val topic = outgoing?.topic ?: deadLetterTopicFor(record.topic)
            val key = outgoing?.key ?: record.key
            val partition = outgoing?.partition?.takeIf { it >= 0 }

            val dead = ProducerRecord(topic, partition, key, record.payload)

            dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_EXCEPTION_CLASS_NAME, reason.javaClass.name)
            dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_REASON, throwableMessage(reason))
            reason.cause?.let { cause ->
                dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE_CLASS_NAME, cause.javaClass.name)
                dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE, throwableMessage(cause))
            }
            dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_TOPIC, record.topic)
            dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_PARTITION, record.partition.toString())
            dead.addHeader(KafkaDeadLetterQueue.DEAD_LETTER_OFFSET, record.offset.toString())
            record.headers.forEach { header -> dead.headers().add(header) }
            outgoing?.headers?.forEach { header -> dead.headers().add(header) }
            // Remove DESERIALIZATION_FAILURE_DLQ header to prevent unconditional DLQ on next consume
            dead.headers().remove(DeserializationFailureHandler.DESERIALIZATION_FAILURE_DLQ)
            return dead
        }

        private fun ProducerRecord<*, *>.addHeader(key: String, value: String) {
            headers().add(key, value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private val log = logger()

    override fun <K, V> handle(
        record: IncomingKafkaRecord<K, V>,
        reason: Throwable,
        metadata: Metadata?,
    ): Uni<Void> {
        @Suppress("UNCHECKED_CAST")
        val outgoing = metadata?.get(OutgoingKafkaRecordMetadata::class.java)?.orElse(null)
            as OutgoingKafkaRecordMetadata<K>?

        val dead = createDeadLetterRecord(record, reason, outgoing)

        log.warn { "A message sent to channel '$channel' has been nacked, sending the record to topic '${dead.topic()}'" }

        val future = CompletableFuture<Void>()
        dlqSource.onNext(
            record.withPayload(dead)
                .withAck { record.ack().thenAccept { future.complete(null) } }
                .withNack { throwable ->
                    future.completeExceptionally(throwable)
                    future
                },
        )
        return Uni.createFrom().completionStage(future)
            .emitOn(Executor { runnable -> record.runOnMessageContext(runnable) })
    }

    override fun terminate() {
        dlqSink.closeQuietly()
    }

    /**
     * CDI factory discovered by SmallRye via `@Identifier` when a channel declares
     * `failure-strategy=lemline-namespace-dlq`.
     *
     * Producer configuration is derived exactly like the stock [KafkaDeadLetterQueue.Factory]:
     * the channel configuration (bootstrap servers, etc.) is reused, key/value serializers are
     * mirrored from the channel's deserializers, and `dead-letter-queue.*` nested channel
     * attributes (e.g. `dead-letter-queue.producer-client-id`) are honored.
     */
    @ApplicationScoped
    @Identifier(STRATEGY)
    class Factory(
        private val kafkaCDIEvents: KafkaCDIEvents,
        @param:Any private val serializationFailureHandlers: Instance<SerializationFailureHandler<*>>,
        @param:Any private val configCustomizers: Instance<ClientCustomizer<MutableMap<String, kotlin.Any>>>,
        @param:Any private val producerInterceptors: Instance<ProducerInterceptor<*, *>>,
        @param:Any private val configurations: Instance<MutableMap<String, kotlin.Any>>,
        private val openTelemetryInstance: Instance<OpenTelemetry>,
        private val subscriberDecorators: Instance<SubscriberDecorator>,
    ) : KafkaFailureHandler.Factory {

        override fun create(
            config: KafkaConnectorIncomingConfiguration,
            vertx: Vertx,
            consumer: KafkaConsumer<*, *>,
            reportFailure: BiConsumer<Throwable, Boolean>,
        ): KafkaFailureHandler {
            // Get (de)serializers and client id from the consumer configuration
            val keyDeserializer = consumer.configuration()[KEY_DESERIALIZER_CLASS_CONFIG] as String?
            val valueDeserializer = consumer.configuration()[VALUE_DESERIALIZER_CLASS_CONFIG] as String?
            val consumerClientId = consumer.configuration()[CLIENT_ID_CONFIG] as String?

            val connectorConfig = OverrideConnectorConfig(
                INCOMING_PREFIX,
                config.config(),
                KafkaConnector.CONNECTOR_NAME,
                config.channel,
                KafkaDeadLetterQueue.CHANNEL_DLQ_SUFFIX,
                mapOf<String, Function<OverrideConnectorConfig, kotlin.Any>>(
                    KEY_SERIALIZER_CLASS_CONFIG to Function { mirrorSerializer(keyDeserializer) },
                    VALUE_SERIALIZER_CLASS_CONFIG to Function { mirrorSerializer(valueDeserializer) },
                    CLIENT_ID_CONFIG to Function {
                        config.deadLetterQueueProducerClientId
                            .orElse("kafka-dead-letter-topic-producer-$consumerClientId")
                    },
                    // Nominal fallback only: the actual topic is set per record in handle()
                    "topic" to Function { deadLetterTopicFor(config.channel) },
                    "key-serialization-failure-handler" to Function { "dlq-serialization" },
                    "value-serialization-failure-handler" to Function { "dlq-serialization" },
                    INTERCEPTOR_CLASSES_CONFIG to Function { "" },
                ),
            )
            val kafkaConfig = ConfigHelper.retrieveChannelConfiguration(configurations, connectorConfig)
            val producerConfig = KafkaConnectorOutgoingConfiguration(kafkaConfig)

            val processor = UnicastProcessor.create<Message<*>>()
            val kafkaSink = KafkaSink(
                producerConfig,
                kafkaCDIEvents,
                openTelemetryInstance,
                configCustomizers,
                serializationFailureHandlers,
                producerInterceptors,
            )
            wireOutgoingConnectorToUpstream(
                processor,
                kafkaSink.sink,
                subscriberDecorators,
                "${producerConfig.channel}-${KafkaDeadLetterQueue.CHANNEL_DLQ_SUFFIX}",
            )
            return NamespaceDeadLetterQueue(config.channel, kafkaSink, processor)
        }
    }
}
