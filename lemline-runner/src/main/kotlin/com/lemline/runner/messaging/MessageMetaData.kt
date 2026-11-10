// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.pgmq.connector.PgmqIncomingMetadata
import com.lemline.runner.messaging.pgmq.connector.PgmqOutgoingMetadata
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class MessageMetaData(
    private val config: LemlineConfiguration
) {
    private val messagingType: MessagingType by lazy { MessagingType.fromConfigValue(config.messaging().type()) }

    private val logger = logger()

    /**
     * Returns a copy of this message carrying [metaData] as broker-specific outgoing metadata.
     * [Message.addMetadata] is immutable — callers MUST use the returned message.
     */
    @Suppress("UNCHECKED_CAST")
    fun <P> Message<P>.withMetaData(metaData: MetaData): Message<P> =
        addMetaDataFunction(this, metaData) as Message<P>

    val Message<*>.messageId get() = getMetaData().messageId

    fun Message<*>.getMetaData() = getMetaDataFunction(this)

    private val getMetaDataFunction by lazy {
        when (messagingType) {
            MessagingType.KAFKA -> { message: Message<*> -> message.kafkaMeta }
            MessagingType.RABBITMQ -> { message: Message<*> -> message.rabbitMeta }
            MessagingType.PGMQ -> { message: Message<*> -> message.pgmqMeta }
            MessagingType.IN_MEMORY -> { message: Message<*> -> message.inMemoryMeta }
        }
    }

    private val Message<*>.kafkaMeta: MetaData
        get() {
            val md = getMetadata(IncomingKafkaRecordMetadata::class.java).orElseThrow()
            val messageId = md.headers.lastHeader(MetaData.MESSAGE_ID)?.value()?.let { IDV7.from(it) }
                ?: error("Message ID is not present in the metadata")

            return MetaData(messageId = messageId)
        }

    private val Message<*>.rabbitMeta: MetaData
        get() {
            val md = getMetadata(IncomingRabbitMQMetadata::class.java).orElseThrow()

            val messageId = md.messageId.getOrNull()?.let { IDV7.from(it) }
                ?: error("Message ID is not present in the metadata")

            return MetaData(messageId = messageId)
        }

    private val Message<*>.pgmqMeta: MetaData
        get() {
            val md = getMetadata(PgmqIncomingMetadata::class.java).orElseThrow()

            // Extract messageId from PGMQ headers JSON
            val messageId = md.headers?.let { headersJson ->
                try {
                    val headers = Json.parseToJsonElement(headersJson).jsonObject
                    headers[MetaData.MESSAGE_ID]?.jsonPrimitive?.content?.let { IDV7.from(it) }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse PGMQ headers: $headersJson" }
                    null
                }
            } ?: error("Message ID is not present in PGMQ headers")

            return MetaData(messageId = messageId)
        }

    private val Message<*>.inMemoryMeta: MetaData
        get() {
            val md = getMetadata(MetaData::class.java).getOrNull()
                ?: error("Message ID is not present in the metadata")

            return md
        }

    private val addMetaDataFunction by lazy {
        when (messagingType) {
            MessagingType.KAFKA -> { message: Message<*>, metaData: MetaData -> message.addKafkaMetaData(metaData) }
            MessagingType.RABBITMQ -> { message: Message<*>, metaData: MetaData -> message.addRabbitMQMeta(metaData) }
            MessagingType.PGMQ -> { message: Message<*>, metaData: MetaData -> message.addPgmqMeta(metaData) }
            MessagingType.IN_MEMORY -> { message: Message<*>, metaData: MetaData -> message.addInMemoryMeta(metaData) }
        }
    }

    private fun Message<*>.addKafkaMetaData(metaData: MetaData): Message<*> {
        val headers = RecordHeaders()
            .add(MetaData.MESSAGE_ID, metaData.messageId.toBytes())
        val builder = OutgoingKafkaRecordMetadata.builder<String>()
            .withHeaders(headers)
        metaData.destination?.let { builder.withTopic(it) }
        return this.addMetadata(builder.build())
    }

    private fun Message<*>.addRabbitMQMeta(metaData: MetaData): Message<*> {
        val builder = OutgoingRabbitMQMetadata.Builder()
            .withMessageId(metaData.messageId.toString())
        metaData.namespace?.let { builder.withRoutingKey(it) }
        return this.addMetadata(builder.build())
    }

    private fun Message<*>.addPgmqMeta(metaData: MetaData): Message<*> {
        // Store messageId in PgmqOutgoingMetadata for the connector to extract
        // The connector will create the headers JSON with this messageId
        val md = PgmqOutgoingMetadata(
            messageId = metaData.messageId.toString(),
            queue = metaData.destination,
        )
        return this.addMetadata(md)
    }

    private fun Message<*>.addInMemoryMeta(md: MetaData): Message<*> = this.addMetadata(md)
}
