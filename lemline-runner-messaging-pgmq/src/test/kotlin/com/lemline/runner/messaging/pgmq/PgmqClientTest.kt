// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq

import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.*
import org.eclipse.microprofile.config.Config

class PgmqClientTest : FunSpec({

    test("PgmqConnectorConfig should use default values when not configured") {
        val config = mockk<Config>()
        every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

        val connectorConfig = PgmqConnectorConfig(config, "test-channel")

        connectorConfig.host shouldBe "localhost"
        connectorConfig.port shouldBe 5432
        connectorConfig.database shouldBe "lemline"
        connectorConfig.username shouldBe "postgres"
        connectorConfig.password shouldBe "postgres"
        connectorConfig.queue shouldBe "test-channel"
        connectorConfig.visibilityTimeout shouldBe 30
        connectorConfig.batchSize shouldBe 10
        connectorConfig.autoCreateQueue shouldBe true
        connectorConfig.maxRetries shouldBe 3
    }

    test("PgmqConnectorConfig should use configured values") {
        val config = mockk<Config>()
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.host",
                String::class.java
            )
        } returns Optional.of("db.example.com")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.port",
                String::class.java
            )
        } returns Optional.of("5433")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.database",
                String::class.java
            )
        } returns Optional.of("mydb")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.username",
                String::class.java
            )
        } returns Optional.of("myuser")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.password",
                String::class.java
            )
        } returns Optional.of("mypass")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.queue",
                String::class.java
            )
        } returns Optional.of("my-queue")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.visibility-timeout",
                String::class.java
            )
        } returns Optional.of("60")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.batch-size",
                String::class.java
            )
        } returns Optional.of("20")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.poll-interval",
                String::class.java
            )
        } returns Optional.of("200")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.max-pool-size",
                String::class.java
            )
        } returns Optional.of("10")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.auto-create-queue",
                String::class.java
            )
        } returns Optional.of("false")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.dead-letter-queue",
                String::class.java
            )
        } returns Optional.of("my-dlq")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.my-channel.max-retries",
                String::class.java
            )
        } returns Optional.of("5")
        // Fallback to outgoing when incoming not found
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.host",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.port",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.database",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.username",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.password",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.queue",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.visibility-timeout",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.batch-size",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.poll-interval",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.max-pool-size",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.auto-create-queue",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.dead-letter-queue",
                String::class.java
            )
        } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.outgoing.my-channel.max-retries",
                String::class.java
            )
        } returns Optional.empty()

        val connectorConfig = PgmqConnectorConfig(config, "my-channel")

        connectorConfig.host shouldBe "db.example.com"
        connectorConfig.port shouldBe 5433
        connectorConfig.database shouldBe "mydb"
        connectorConfig.username shouldBe "myuser"
        connectorConfig.password shouldBe "mypass"
        connectorConfig.queue shouldBe "my-queue"
        connectorConfig.visibilityTimeout shouldBe 60
        connectorConfig.batchSize shouldBe 20
        connectorConfig.autoCreateQueue shouldBe false
        connectorConfig.deadLetterQueue shouldBe "my-dlq"
        connectorConfig.maxRetries shouldBe 5
    }

    test("PgmqConnectorConfig should generate correct JDBC URL") {
        val config = mockk<Config>()
        // Order matters: any() first, specific matchers after to override
        every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.test.host",
                String::class.java
            )
        } returns Optional.of("myhost")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.test.port",
                String::class.java
            )
        } returns Optional.of("5433")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.test.database",
                String::class.java
            )
        } returns Optional.of("mydb")

        val connectorConfig = PgmqConnectorConfig(config, "test")

        connectorConfig.toJdbcUrl() shouldBe "jdbc:postgresql://myhost:5433/mydb"
    }

    test("PgmqMessage should store message data correctly") {
        val now = java.time.Instant.now()
        val message = PgmqMessage(
            msgId = 123L,
            readCt = 2,
            enqueuedAt = now,
            vt = now.plusSeconds(30),
            message = """{"key": "value"}"""
        )

        message.msgId shouldBe 123L
        message.readCt shouldBe 2
        message.enqueuedAt shouldBe now
        message.vt shouldBe now.plusSeconds(30)
        message.message shouldBe """{"key": "value"}"""
    }

    test("DlqMessage should serialize correctly") {
        val dlqMessage = DlqMessage(
            originalMessageId = 456L,
            originalQueue = "test-queue",
            payload = """{"data": "test"}""",
            error = "Processing failed",
            timestamp = "2024-01-15T10:30:00Z"
        )

        dlqMessage.originalMessageId shouldBe 456L
        dlqMessage.originalQueue shouldBe "test-queue"
        dlqMessage.payload shouldBe """{"data": "test"}"""
        dlqMessage.error shouldBe "Processing failed"
        dlqMessage.timestamp shouldBe "2024-01-15T10:30:00Z"
    }
})
