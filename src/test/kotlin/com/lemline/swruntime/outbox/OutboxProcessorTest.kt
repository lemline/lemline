package com.lemline.swruntime.outbox

import com.lemline.swruntime.metrics.OutboxMetrics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.slf4j.Logger
import java.time.Instant
import java.util.*
import kotlin.math.absoluteValue

class OutboxProcessorTest : FunSpec({
    val logger = mockk<Logger>(relaxed = true)
    val repository = mockk<OutboxRepository<TestMessage>>()
    val metrics = mockk<OutboxMetrics>(relaxed = true)
    val processor = mockk<(TestMessage) -> Unit>()
    val type = "test"
    lateinit var outboxProcessor: OutboxProcessor<TestMessage>

    beforeTest {
        outboxProcessor = OutboxProcessor(logger, repository, processor, metrics, type)
    }

    context("calculateNextRetryDelay") {
        test("should use exponential backoff with jitter") {
            val initialDelay = 10
            val attempts = listOf(1, 2, 3)
            val expectedBaseTimes = listOf(10L, 20L, 40L)

            attempts.zip(expectedBaseTimes).forEach { (attempt, expectedBase) ->
                val delays = (1..100).map {
                    outboxProcessor.calculateNextRetryDelay(attempt, initialDelay)
                }

                // Check average is close to expected base time (within 5%)
                val average = delays.average()
                (average - expectedBase).absoluteValue shouldBe (expectedBase * 0.05).plusOrMinus(0.01)

                // Check jitter is within ±20%
                delays.forEach { delay ->
                    val jitterRatio = (delay - expectedBase).absoluteValue.toDouble() / expectedBase
                    jitterRatio shouldBe 0.2.plusOrMinus(0.01)
                }
            }
        }

        test("should never return less than 1 second") {
            val delay = outboxProcessor.calculateNextRetryDelay(1, 0)
            delay shouldBe 1L
        }
    }

    context("process") {
        test("should record metrics for successful message processing") {
            val message = TestMessage(
                id = UUID.randomUUID(),
                status = OutBoxStatus.PENDING,
                attemptCount = 0,
                lastError = null,
                delayedUntil = Instant.now()
            )

            every { repository.findAndLockReadyToProcess(any(), any()) } returns listOf(message) andThen emptyList()
            every { repository.count("status = ?1", OutBoxStatus.PENDING) } returns 42
            every { processor(message) } just runs

            outboxProcessor.process(10, 3, 10)

            verify {
                metrics.recordProcessedMessage(any(), type)
                metrics.recordBatchProcessingTime(any(), 1, type)
                metrics.updatePendingMessages(42, type)
            }
            verify(exactly = 0) { metrics.recordFailedMessage(any()) }
        }

        test("should record metrics for failed message processing") {
            val message = TestMessage(
                id = UUID.randomUUID(),
                status = OutBoxStatus.PENDING,
                attemptCount = 0,
                lastError = null,
                delayedUntil = Instant.now()
            )

            every { repository.findAndLockReadyToProcess(any(), any()) } returns listOf(message) andThen emptyList()
            every { repository.count("status = ?1", OutBoxStatus.PENDING) } returns 42
            every { processor(message) } throws RuntimeException("Test error")

            outboxProcessor.process(10, 3, 10)

            verify {
                metrics.recordFailedMessage(type)
                metrics.updatePendingMessages(42, type)
            }
            verify(exactly = 0) { metrics.recordProcessedMessage(any(), any()) }
        }
    }
})

data class TestMessage(
    override var id: UUID?,
    override var status: OutBoxStatus,
    override var attemptCount: Int,
    override var lastError: String?,
    override var delayedUntil: Instant
) : OutboxMessage 