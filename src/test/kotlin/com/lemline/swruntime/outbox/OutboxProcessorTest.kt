package com.lemline.swruntime.outbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import kotlin.math.absoluteValue

class OutboxProcessorTest : FunSpec({
    val logger = mockk<Logger>(relaxed = true)
    val repository = mockk<OutboxRepository<OutboxMessage>>()
    val processor = mockk<(OutboxMessage) -> Unit>()
    val outboxProcessor = OutboxProcessor(logger, repository, processor)

    context("calculateNextRetryDelay") {
        test("should respect exponential backoff with 20% jitter") {
            val initialDelay = 10
            val attempts = listOf(1, 2, 3, 4)

            attempts.forEach { attempt ->
                // Calculate expected base delay: initialDelay * 2^(attempt-1)
                val expectedBaseDelay = initialDelay * (1L shl (attempt - 1))
                val jitterRange = (expectedBaseDelay * 0.2).toLong()

                // Run multiple times to test jitter distribution
                repeat(100) {
                    val actualDelay = outboxProcessor.calculateNextRetryDelay(attempt, initialDelay)

                    // Verify delay is within expected range (base ± 20%)
                    actualDelay.shouldBeBetween(
                        expectedBaseDelay - jitterRange,
                        expectedBaseDelay + jitterRange
                    )

                    // Verify delay is never less than 1 second
                    actualDelay shouldBe actualDelay.coerceAtLeast(1)
                }
            }
        }

        test("should never return less than 1 second") {
            // Test with very small initial delay and attempt count
            val delay = outboxProcessor.calculateNextRetryDelay(1, 0)
            delay shouldBe 1L
        }

        test("should have roughly symmetric jitter distribution") {
            val initialDelay = 100
            val attempt = 1
            val expectedBaseDelay = initialDelay * (1L shl (attempt - 1))
            val delays = List(1000) { outboxProcessor.calculateNextRetryDelay(attempt, initialDelay) }

            // Calculate average delay
            val averageDelay = delays.average()
            
            // Average should be close to base delay (within 2%)
            (averageDelay - expectedBaseDelay).absoluteValue shouldBe (0.0 plusOrMinus (expectedBaseDelay * 0.02))

            // Calculate distribution of jitter
            val jitters = delays.map { it - expectedBaseDelay }
            val positiveJitters = jitters.count { it > 0 }
            val negativeJitters = jitters.count { it < 0 }

            // Ratio of positive to negative jitters should be roughly 1:1 (within 10%)
            (positiveJitters.toDouble() / negativeJitters - 1.0).absoluteValue shouldBe (0.0 plusOrMinus 0.1)
        }
    }
}) 