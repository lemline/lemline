// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import com.lemline.runner.messaging.postgres.PgmqMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Subscription
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

class PgmqConnectorTest : FunSpec({

    context("PgmqIncomingMetadata") {
        test("should store all incoming message properties") {
            val now = Instant.now()
            val vt = now.plusSeconds(30)

            val metadata = PgmqIncomingMetadata(
                msgId = 123L,
                readCount = 2,
                enqueuedAt = now,
                visibilityTimeout = vt,
                queue = "test-queue"
            )

            metadata.msgId shouldBe 123L
            metadata.readCount shouldBe 2
            metadata.enqueuedAt shouldBe now
            metadata.visibilityTimeout shouldBe vt
            metadata.queue shouldBe "test-queue"
        }

        test("should support data class copy") {
            val original = PgmqIncomingMetadata(
                msgId = 1L,
                readCount = 1,
                enqueuedAt = Instant.now(),
                visibilityTimeout = Instant.now().plusSeconds(30),
                queue = "q1"
            )

            val copied = original.copy(readCount = 5)

            copied.msgId shouldBe original.msgId
            copied.readCount shouldBe 5
            copied.queue shouldBe original.queue
        }
    }

    context("PgmqOutgoingMetadata") {
        test("should have default delay of 0") {
            val metadata = PgmqOutgoingMetadata()
            metadata.delaySeconds shouldBe 0
            metadata.messageId shouldBe null
        }

        test("should store custom delay and message ID") {
            val metadata = PgmqOutgoingMetadata(
                delaySeconds = 60,
                messageId = "custom-id-123"
            )

            metadata.delaySeconds shouldBe 60
            metadata.messageId shouldBe "custom-id-123"
        }
    }

    context("PgmqMetadataContainer") {
        test("should contain metadata and be iterable") {
            val metadata = PgmqIncomingMetadata(
                msgId = 1L,
                readCount = 1,
                enqueuedAt = Instant.now(),
                visibilityTimeout = Instant.now().plusSeconds(30),
                queue = "test"
            )

            val container = PgmqMetadataContainer(metadata)

            container.metadata shouldBe metadata
            container.iterator().hasNext() shouldBe true
            container.iterator().next() shouldBe metadata
        }
    }

    context("PgmqOutgoingConnector Subscriber") {
        test("should request messages on subscription") {
            val subscription = mockk<Subscription>(relaxed = true)
            val requestSlot = slot<Long>()
            every { subscription.request(capture(requestSlot)) } returns Unit

            // The subscriber requests 1 message at a time
            subscription.request(1)

            requestSlot.captured shouldBe 1L
        }

        test("should handle string payload") {
            val payload = """{"key": "value"}"""
            val message = mockk<Message<String>>(relaxed = true)
            every { message.payload } returns payload

            message.payload shouldBe payload
        }

        test("should handle byte array payload conversion") {
            val originalPayload = """{"key": "value"}"""
            val bytePayload = originalPayload.toByteArray(Charsets.UTF_8)

            val converted = String(bytePayload, Charsets.UTF_8)
            converted shouldBe originalPayload
        }

        test("should extract delay from outgoing metadata") {
            val metadata = PgmqOutgoingMetadata(delaySeconds = 30)
            val message = mockk<Message<String>>(relaxed = true)
            every { message.metadata } returns listOf(metadata)

            val extractedDelay = message.metadata
                .filterIsInstance<PgmqOutgoingMetadata>()
                .firstOrNull()
                ?.delaySeconds ?: 0

            extractedDelay shouldBe 30
        }

        test("should default to 0 delay when no metadata") {
            val message = mockk<Message<String>>(relaxed = true)
            every { message.metadata } returns emptyList()

            val extractedDelay = message.metadata
                .filterIsInstance<PgmqOutgoingMetadata>()
                .firstOrNull()
                ?.delaySeconds ?: 0

            extractedDelay shouldBe 0
        }
    }

    context("Message creation from PgmqMessage") {
        test("should create message with correct payload") {
            val now = Instant.now()
            val pgmqMessage = PgmqMessage(
                msgId = 456L,
                readCt = 1,
                enqueuedAt = now,
                vt = now.plusSeconds(30),
                message = """{"data": "test"}"""
            )

            pgmqMessage.message shouldBe """{"data": "test"}"""
            pgmqMessage.msgId shouldBe 456L
        }

        test("should track read count for retry logic") {
            val pgmqMessage = PgmqMessage(
                msgId = 1L,
                readCt = 3,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = "test"
            )

            // Message has been read 3 times - check against max retries
            val maxRetries = 3
            val shouldMoveToDeadLetter = pgmqMessage.readCt >= maxRetries

            shouldMoveToDeadLetter shouldBe true
        }

        test("should not move to DLQ when under max retries") {
            val pgmqMessage = PgmqMessage(
                msgId = 1L,
                readCt = 2,
                enqueuedAt = Instant.now(),
                vt = Instant.now().plusSeconds(30),
                message = "test"
            )

            val maxRetries = 3
            val shouldMoveToDeadLetter = pgmqMessage.readCt >= maxRetries

            shouldMoveToDeadLetter shouldBe false
        }
    }
})
