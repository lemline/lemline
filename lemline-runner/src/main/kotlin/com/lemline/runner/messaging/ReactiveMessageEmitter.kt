// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_RABBITMQ
import com.lemline.runner.config.MESSAGING_TYPE
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneId
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@Startup
@ApplicationScoped
internal class ReactiveMessageEmitter {

    @Channel(WORKFLOW_OUT)
    private lateinit var emitter: MutinyEmitter<String>

    @ConfigProperty(name = MESSAGING_TYPE)
    private lateinit var messagingType: String

    suspend fun send(payload: String) {
        emit(payload, getMetaData())
    }

    suspend fun emit(payload: String, metadata: Any): Void? =
        emitter.sendMessage(Message.of(payload).addMetadata(metadata)).awaitSuspending()

    private fun getMetaData(): Any =
        when (messagingType) {
            MSG_TYPE_KAFKA -> getKafkaMetaData()
            MSG_TYPE_RABBITMQ -> getRabbitMQMeta()
            MSG_TYPE_IN_MEMORY -> getInMemoryMeta()
            else -> error("Unknown messaging type: $messagingType")
        }

    private fun getKafkaMetaData() = OutgoingKafkaRecordMetadata.builder<String>()
        .build()

    private fun getRabbitMQMeta() = OutgoingRabbitMQMetadata.Builder()
        .withMessageId("order-${System.nanoTime()}")
        .withTimestamp(Instant.now().atZone(ZoneId.of("UTC")))
        .withHeader("Idempotency-Key", "abc-123")
        .build()

    private fun getInMemoryMeta() = InMemoryMetaData(
        id = "order-${System.nanoTime()}",
        timestamp = Instant.now()
    )

}
