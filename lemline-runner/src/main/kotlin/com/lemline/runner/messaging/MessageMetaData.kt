// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_KAFKA
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_RABBITMQ
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import java.time.Instant
import java.time.ZoneId
import org.eclipse.microprofile.reactive.messaging.Message

class MessageMetaData(
    private val messagingType: String,
) {
    private val Message<*>.meta: Pair<String, Instant>
        get() = when (messagingType) {
            MSG_TYPE_KAFKA -> kafkaMeta
            MSG_TYPE_RABBITMQ -> rabbitMeta
            MSG_TYPE_IN_MEMORY -> inMemoryMeta
            else -> error("Unknown messaging type: $messagingType")
        }

    private val Message<*>.kafkaMeta: Pair<String, Instant>
        get() {
            val md = getMetadata(IncomingKafkaRecordMetadata::class.java).orElseThrow()
            return "${md.topic}-${md.partition}-${md.offset}" to md.timestamp
        }

    private val Message<*>.rabbitMeta: Pair<String, Instant>
        get() {
            val md = getMetadata(IncomingRabbitMQMetadata::class.java).orElseThrow()
            return md.messageId.orElseThrow() to Instant.from(md.getTimestamp(ZoneId.of("UTC")).orElseThrow())
        }

    private val Message<*>.inMemoryMeta: Pair<String, Instant>
        get() {
            val md = getMetadata(InMemoryMetaData::class.java).orElseThrow()
            return md.id to md.timestamp
        }
}
