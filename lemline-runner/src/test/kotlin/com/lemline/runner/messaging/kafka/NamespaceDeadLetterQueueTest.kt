// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.kafka

import io.mockk.every
import io.mockk.mockk
import io.smallrye.reactive.messaging.kafka.DeserializationFailureHandler
import io.smallrye.reactive.messaging.kafka.IncomingKafkaRecord
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.kafka.fault.KafkaDeadLetterQueue
import java.nio.charset.StandardCharsets
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class NamespaceDeadLetterQueueTest {

    private fun incomingRecord(
        topic: String = "lemline-acme-commands",
        partition: Int = 3,
        offset: Long = 42L,
        key: String? = "key-1",
        payload: String = "payload-1",
        headers: RecordHeaders = RecordHeaders(),
    ): IncomingKafkaRecord<String, String> {
        val record = mockk<IncomingKafkaRecord<String, String>>()
        every { record.topic } returns topic
        every { record.partition } returns partition
        every { record.offset } returns offset
        every { record.key } returns key
        every { record.payload } returns payload
        every { record.headers } returns headers
        return record
    }

    private fun org.apache.kafka.clients.producer.ProducerRecord<*, *>.headerValue(name: String): String? =
        headers().lastHeader(name)?.let { String(it.value(), StandardCharsets.UTF_8) }

    @Test
    fun `should derive dead-letter topic from source topic`() {
        assertEquals(
            "lemline-acme-commands.dlq",
            NamespaceDeadLetterQueue.deadLetterTopicFor("lemline-acme-commands"),
        )
        assertEquals(
            "lemline-team-events.dlq",
            NamespaceDeadLetterQueue.deadLetterTopicFor("lemline-team-events"),
        )
    }

    @Test
    fun `should target the source-topic-derived dlq topic per record`() {
        val record = incomingRecord(topic = "lemline-acme-commands")

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, RuntimeException("boom"), null)

        assertEquals("lemline-acme-commands.dlq", dead.topic())
    }

    @Test
    fun `records from different namespace topics go to different dlq topics`() {
        val reason = RuntimeException("boom")

        val acme = NamespaceDeadLetterQueue.createDeadLetterRecord(
            incomingRecord(topic = "lemline-acme-commands"), reason, null,
        )
        val team = NamespaceDeadLetterQueue.createDeadLetterRecord(
            incomingRecord(topic = "lemline-team-commands"), reason, null,
        )

        assertEquals("lemline-acme-commands.dlq", acme.topic())
        assertEquals("lemline-team-commands.dlq", team.topic())
    }

    @Test
    fun `should keep key and payload of the failed record`() {
        val record = incomingRecord(key = "key-1", payload = "payload-1")

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, RuntimeException("boom"), null)

        assertEquals("key-1", dead.key())
        assertEquals("payload-1", dead.value())
        assertNull(dead.partition())
    }

    @Test
    fun `should write the stock diagnostic headers`() {
        val record = incomingRecord(topic = "lemline-acme-commands", partition = 3, offset = 42L)
        val reason = IllegalStateException("boom", IllegalArgumentException("root cause"))

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, reason, null)

        assertEquals(
            IllegalStateException::class.java.name,
            dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_EXCEPTION_CLASS_NAME),
        )
        assertEquals("boom", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_REASON))
        assertEquals(
            IllegalArgumentException::class.java.name,
            dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE_CLASS_NAME),
        )
        assertEquals("root cause", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE))
        assertEquals("lemline-acme-commands", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_TOPIC))
        assertEquals("3", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_PARTITION))
        assertEquals("42", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_OFFSET))
    }

    @Test
    fun `should omit cause headers when the reason has no cause`() {
        val record = incomingRecord()

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, RuntimeException("boom"), null)

        assertNull(dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE_CLASS_NAME))
        assertNull(dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_CAUSE))
    }

    @Test
    fun `should propagate incoming record headers and drop the deserialization-failure marker`() {
        val headers = RecordHeaders()
        headers.add("custom-header", "custom-value".toByteArray(StandardCharsets.UTF_8))
        headers.add(
            DeserializationFailureHandler.DESERIALIZATION_FAILURE_DLQ,
            "true".toByteArray(StandardCharsets.UTF_8),
        )
        val record = incomingRecord(headers = headers)

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, RuntimeException("boom"), null)

        assertEquals("custom-value", dead.headerValue("custom-header"))
        assertNull(dead.headers().lastHeader(DeserializationFailureHandler.DESERIALIZATION_FAILURE_DLQ))
    }

    @Test
    fun `explicit outgoing metadata still overrides topic key and partition as in the stock dlq`() {
        val record = incomingRecord(topic = "lemline-acme-commands", key = "key-1")
        val outgoing = OutgoingKafkaRecordMetadata.builder<String>()
            .withTopic("explicit-topic")
            .withKey("explicit-key")
            .withPartition(7)
            .build()

        val dead = NamespaceDeadLetterQueue.createDeadLetterRecord(record, RuntimeException("boom"), outgoing)

        assertEquals("explicit-topic", dead.topic())
        assertEquals("explicit-key", dead.key())
        assertEquals(7, dead.partition())
        // original coordinates still point to the source record
        assertEquals("lemline-acme-commands", dead.headerValue(KafkaDeadLetterQueue.DEAD_LETTER_TOPIC))
    }

    @Test
    fun `should mirror the channel deserializers into producer serializers`() {
        assertEquals(
            StringSerializer::class.java.name,
            NamespaceDeadLetterQueue.mirrorSerializer("org.apache.kafka.common.serialization.StringDeserializer"),
        )
        assertEquals(
            ByteArraySerializer::class.java.name,
            NamespaceDeadLetterQueue.mirrorSerializer("org.apache.kafka.common.serialization.ByteArrayDeserializer"),
        )
        // default when the consumer has no explicit deserializer
        assertEquals(StringSerializer::class.java.name, NamespaceDeadLetterQueue.mirrorSerializer(null))
    }

    @Test
    fun `should fall back to toString when the throwable has no message`() {
        assertEquals("boom", NamespaceDeadLetterQueue.throwableMessage(RuntimeException("boom")))
        val noMessage = RuntimeException()
        assertEquals(noMessage.toString(), NamespaceDeadLetterQueue.throwableMessage(noMessage))
    }
}
