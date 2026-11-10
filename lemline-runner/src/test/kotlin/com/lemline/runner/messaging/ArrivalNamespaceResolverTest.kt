// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.pgmq.connector.PgmqIncomingMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import java.time.Instant
import java.util.Optional
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ArrivalNamespaceResolver].
 *
 * Verifies per-broker resolution of the namespace of the destination a message arrived on,
 * and that unparsable destinations fail loudly instead of allowing cross-namespace matching.
 */
@DisplayName("ArrivalNamespaceResolver")
class ArrivalNamespaceResolverTest {

    private fun resolver(
        type: MessagingType,
        prefix: String = "lemline",
        followed: Set<String> = setOf("default"),
    ): ArrivalNamespaceResolver {
        val config = mockk<LemlineConfiguration>()
        every { config.messaging().type() } returns type.configValue
        every { config.messaging().prefix() } returns prefix
        val scope = object : NamespaceScope {
            override val configured: Set<String> = followed
        }
        return ArrivalNamespaceResolver(config, scope)
    }

    // ============================================================================
    // Kafka
    // ============================================================================

    @Nested
    @DisplayName("With Kafka messaging")
    inner class KafkaTests {

        private fun kafkaMessage(topic: String): Message<String> {
            val md = IncomingKafkaRecordMetadata(
                ConsumerRecord<String, String>(topic, 0, 0L, null, "{}"),
                "cloudevents-in"
            )
            return Message.of("{}", Metadata.of(md))
        }

        @Test
        fun `should resolve the namespace from the topic name`() {
            resolver(MessagingType.KAFKA)
                .resolve(kafkaMessage("lemline-team-a-cloudevents"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("team-a"))
        }

        @Test
        fun `should honor a custom destination prefix`() {
            resolver(MessagingType.KAFKA, prefix = "acme")
                .resolve(kafkaMessage("acme-prod-cloudevents"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("prod"))
        }

        @Test
        fun `should fail on a topic without a namespace segment`() {
            val e = shouldThrow<IllegalStateException> {
                resolver(MessagingType.KAFKA)
                    .resolve(kafkaMessage("lemline-cloudevents"), DestinationType.CLOUDEVENTS)
            }
            e.message!! shouldContain "lemline-cloudevents"
        }

        @Test
        fun `should fail on a topic not following the convention`() {
            shouldThrow<IllegalStateException> {
                resolver(MessagingType.KAFKA)
                    .resolve(kafkaMessage("some-other-topic"), DestinationType.CLOUDEVENTS)
            }
        }

        @Test
        fun `should fail when the Kafka metadata is missing`() {
            shouldThrow<IllegalStateException> {
                resolver(MessagingType.KAFKA).resolve(Message.of("{}"), DestinationType.CLOUDEVENTS)
            }
        }
    }

    // ============================================================================
    // RabbitMQ
    // ============================================================================

    @Nested
    @DisplayName("With RabbitMQ messaging")
    inner class RabbitMQTests {

        private fun rabbitMessage(exchange: String, routingKey: String): Message<String> {
            val md = mockk<IncomingRabbitMQMetadata>()
            every { md.exchange } returns exchange
            every { md.routingKey } returns routingKey
            val message = mockk<Message<String>>()
            every { message.getMetadata(IncomingRabbitMQMetadata::class.java) } returns Optional.of(md)
            return message
        }

        @Test
        fun `should resolve the namespace from a per-namespace exchange`() {
            resolver(MessagingType.RABBITMQ)
                .resolve(rabbitMessage("lemline-team-a-cloudevents", "ignored"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("team-a"))
        }

        @Test
        fun `should fall back to the routing key when the exchange is not per-namespace`() {
            // Internal Emit publications go to the channel exchange with routing key = namespace
            resolver(MessagingType.RABBITMQ)
                .resolve(rabbitMessage("lemline-cloudevents", "team-b"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("team-b"))
        }

        @Test
        fun `should fail when neither exchange nor routing key carries a namespace`() {
            val e = shouldThrow<IllegalStateException> {
                resolver(MessagingType.RABBITMQ)
                    .resolve(rabbitMessage("lemline-cloudevents", ""), DestinationType.CLOUDEVENTS)
            }
            e.message!! shouldContain "lemline-cloudevents"
        }

        @Test
        fun `should fail when the RabbitMQ metadata is missing`() {
            shouldThrow<IllegalStateException> {
                resolver(MessagingType.RABBITMQ).resolve(Message.of("{}"), DestinationType.CLOUDEVENTS)
            }
        }
    }

    // ============================================================================
    // PGMQ
    // ============================================================================

    @Nested
    @DisplayName("With PGMQ messaging")
    inner class PgmqTests {

        private fun pgmqMessage(queue: String): Message<String> {
            val md = PgmqIncomingMetadata(
                msgId = 1L,
                readCount = 1,
                enqueuedAt = Instant.now(),
                visibilityTimeout = Instant.now(),
                queue = queue,
            )
            return Message.of("{}", Metadata.of(md))
        }

        @Test
        fun `should resolve the namespace from the queue name`() {
            resolver(MessagingType.PGMQ)
                .resolve(pgmqMessage("lemline-team-a-cloudevents"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("team-a"))
        }

        @Test
        fun `should fail on a queue without a namespace segment`() {
            val e = shouldThrow<IllegalStateException> {
                resolver(MessagingType.PGMQ)
                    .resolve(pgmqMessage("lemline-cloudevents"), DestinationType.CLOUDEVENTS)
            }
            e.message!! shouldContain "lemline-cloudevents"
        }

        @Test
        fun `should fail when the PGMQ metadata is missing`() {
            shouldThrow<IllegalStateException> {
                resolver(MessagingType.PGMQ).resolve(Message.of("{}"), DestinationType.CLOUDEVENTS)
            }
        }
    }

    // ============================================================================
    // In-memory
    // ============================================================================

    @Nested
    @DisplayName("With in-memory messaging")
    inner class InMemoryTests {

        @Test
        fun `should resolve the namespace from the MetaData when present`() {
            val message = Message.of(
                "{}",
                Metadata.of(MetaData(messageId = IDV7.random(), namespace = "team-a"))
            )
            resolver(MessagingType.IN_MEMORY, followed = setOf("team-a", "team-b"))
                .resolve(message, DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("team-a"))
        }

        @Test
        fun `should fall back to the single followed namespace when MetaData is absent`() {
            resolver(MessagingType.IN_MEMORY, followed = setOf("only-one"))
                .resolve(Message.of("{}"), DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("only-one"))
        }

        @Test
        fun `should fall back when MetaData is present but carries no namespace`() {
            val message = Message.of("{}", Metadata.of(MetaData(messageId = IDV7.random())))
            resolver(MessagingType.IN_MEMORY, followed = setOf("only-one"))
                .resolve(message, DestinationType.CLOUDEVENTS)
                .shouldBe(WorkflowNamespace("only-one"))
        }

        @Test
        fun `should fail without MetaData when several namespaces are followed`() {
            val e = shouldThrow<IllegalStateException> {
                resolver(MessagingType.IN_MEMORY, followed = setOf("team-a", "team-b"))
                    .resolve(Message.of("{}"), DestinationType.CLOUDEVENTS)
            }
            e.message!! shouldContain "expected exactly one"
        }
    }
}
