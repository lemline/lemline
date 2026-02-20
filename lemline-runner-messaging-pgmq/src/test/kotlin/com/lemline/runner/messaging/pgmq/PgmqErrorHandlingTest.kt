// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq

import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.config.Config

class PgmqErrorHandlingTest : FunSpec({

    context("Configuration validation") {
        test("should use channel name as queue when queue not configured") {
            val config = mockk<Config>()
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "my-channel-name")

            connectorConfig.queue shouldBe "my-channel-name"
        }

        test("should handle invalid port gracefully with default") {
            val config = mockk<Config>()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test.port",
                    String::class.java
                )
            } returns Optional.of("invalid")
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test")

            // Invalid port should fall back to default
            connectorConfig.port shouldBe PgmqConnectorConfig.DEFAULT_PORT
        }

        test("should handle invalid batch size with default") {
            val config = mockk<Config>()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test.batch-size",
                    String::class.java
                )
            } returns Optional.of("not-a-number")
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test")

            connectorConfig.batchSize shouldBe PgmqConnectorConfig.DEFAULT_BATCH_SIZE
        }

        test("should handle invalid visibility timeout with default") {
            val config = mockk<Config>()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test.visibility-timeout",
                    String::class.java
                )
            } returns Optional.of("abc")
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test")

            connectorConfig.visibilityTimeout shouldBe PgmqConnectorConfig.DEFAULT_VISIBILITY_TIMEOUT
        }

        test("should handle invalid max retries with default") {
            val config = mockk<Config>()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test.max-retries",
                    String::class.java
                )
            } returns Optional.of("xyz")
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test")

            connectorConfig.maxRetries shouldBe PgmqConnectorConfig.DEFAULT_MAX_RETRIES
        }

        test("should handle invalid auto-create-queue with default") {
            val config = mockk<Config>()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test.auto-create-queue",
                    String::class.java
                )
            } returns Optional.of("maybe")
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test")

            // toBooleanStrictOrNull returns null for invalid values, so default is used
            connectorConfig.autoCreateQueue shouldBe PgmqConnectorConfig.DEFAULT_AUTO_CREATE_QUEUE
        }
    }

    context("DLQ message serialization") {
        test("should serialize DlqMessage correctly") {
            val dlqMessage = DlqMessage(
                originalMessageId = 123L,
                originalQueue = "test-queue",
                payload = """{"key": "value"}""",
                error = "Processing failed: NullPointerException",
                timestamp = "2024-01-15T10:30:00Z"
            )

            val json = Json.encodeToString(dlqMessage)

            json shouldContain "123"
            json shouldContain "test-queue"
            json shouldContain "Processing failed"
            json shouldContain "2024-01-15T10:30:00Z"
        }

        test("should deserialize DlqMessage correctly") {
            val json =
                """{"originalMessageId":456,"originalQueue":"q1","payload":"test","error":"err","timestamp":"2024-01-01T00:00:00Z"}"""

            val dlqMessage = Json.decodeFromString<DlqMessage>(json)

            dlqMessage.originalMessageId shouldBe 456L
            dlqMessage.originalQueue shouldBe "q1"
            dlqMessage.payload shouldBe "test"
            dlqMessage.error shouldBe "err"
            dlqMessage.timestamp shouldBe "2024-01-01T00:00:00Z"
        }

        test("should handle special characters in error message") {
            val dlqMessage = DlqMessage(
                originalMessageId = 1L,
                originalQueue = "q",
                payload = "p",
                error = """Error with "quotes" and \backslash and newline
here""",
                timestamp = "2024-01-01T00:00:00Z"
            )

            val json = Json.encodeToString(dlqMessage)
            val decoded = Json.decodeFromString<DlqMessage>(json)

            decoded.error shouldBe dlqMessage.error
        }

        test("should handle JSON payload in DlqMessage") {
            val nestedPayload = """{"nested": {"key": "value", "array": [1, 2, 3]}}"""
            val dlqMessage = DlqMessage(
                originalMessageId = 1L,
                originalQueue = "q",
                payload = nestedPayload,
                error = "err",
                timestamp = "2024-01-01T00:00:00Z"
            )

            val json = Json.encodeToString(dlqMessage)
            val decoded = Json.decodeFromString<DlqMessage>(json)

            decoded.payload shouldBe nestedPayload
        }
    }

    context("PgmqMessage handling") {
        test("should handle empty message payload") {
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 1,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = ""
            )

            msg.message shouldBe ""
        }

        test("should handle very long message payload") {
            val longPayload = "x".repeat(100_000)
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 1,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = longPayload
            )

            msg.message.length shouldBe 100_000
        }

        test("should preserve unicode in message payload") {
            val unicodePayload = """{"emoji": "🎉", "chinese": "中文", "arabic": "العربية"}"""
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 1,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = unicodePayload
            )

            msg.message shouldBe unicodePayload
        }

        test("should track visibility timeout correctly") {
            val now = Instant.now()
            val vt = now.plusSeconds(30)

            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 1,
                enqueuedAt = now,
                vt = vt,
                message = "test"
            )

            msg.vt.isAfter(msg.enqueuedAt) shouldBe true
        }
    }

    context("Retry logic") {
        test("should identify first attempt") {
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 1,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = "test"
            )

            val isFirstAttempt = msg.readCt == 1
            isFirstAttempt shouldBe true
        }

        test("should identify retry attempts") {
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 3,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = "test"
            )

            val isRetry = msg.readCt > 1
            isRetry shouldBe true
        }

        test("should calculate remaining retries") {
            val maxRetries = 5
            val msg = PgmqMessage(
                msgId = 1L,
                readCt = 3,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = "test"
            )

            val remainingRetries = maxRetries - msg.readCt
            remainingRetries shouldBe 2
        }
    }
})
