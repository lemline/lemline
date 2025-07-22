package com.lemline.runner.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.toJavaDuration


/**
 * Provides metrics for monitoring workflow message processing.
 * Uses Micrometer for metrics collection and reporting.
 */
@ApplicationScoped
class MessageSubscriberMetrics @Inject constructor(
    registry: MeterRegistry
) {
    /** Tracks the number of messages currently being processed */
    private val activeMessages = AtomicInteger(0)

    /** Counts total received messages */
    private val receivedCounter: Counter =
        registry.counter("lemline.message.received.total")

    /** Counts total acknowledged message */
    private val acknowledgedCounter: Counter =
        registry.counter("lemline.message.acknowledged.total")

    /** Counts total unacknowledged message */
    private val unacknowledgedCounter: Counter =
        registry.counter("lemline.message.unacknowledged.total")

    /** Counts total successfully processed messages */
    private val processedCounter: Counter =
        registry.counter("lemline.message.processed.total")

    /** Counts total failed message processing */
    private val failedCounter: Counter =
        registry.counter("lemline.message.failed.total")

    /** Counts occurrences of processing saturation (max parallelism reached) */
    private val saturationCounter: Counter =
        registry.counter("lemline.message.parallelism.saturation.total")

    /** Measures workflow message processing duration */
    private val processingTimer: Timer =
        registry.timer("lemline.message.processing.duration")

    init {
        Gauge.builder("lemline.message.active", activeMessages) { it.toDouble() }
            .description("Current number of workflow messages being processed")
            .register(registry)
    }

    /** Increments and returns the count of active messages */
    fun incrementActive(): Int = activeMessages.incrementAndGet()

    /** Decrements and returns the count of active messages */
    fun decrementActive(): Int = activeMessages.decrementAndGet()

    /** Increments the counter for received messages */
    fun received() = receivedCounter.increment()

    /** Increments the counter for acknowledged messages */
    fun acknowledged() = acknowledgedCounter.increment()

    /** Increments the counter for unacknowledged messages */
    fun unacknowledged() = unacknowledgedCounter.increment()

    /** Increments the counter for successfully processed messages */
    fun processed() = processedCounter.increment()

    /** Increments the counter for failed message processing attempts */
    fun failed() = failedCounter.increment()

    /** Increments the counter for processing saturation events */
    fun saturated() = saturationCounter.increment()

    /** Returns the current count of active messages */
    fun getActiveCount(): Int = activeMessages.get()

    /**
     * Records the duration of a suspending block of code.
     *
     * @param block The suspending code block to measure
     * @return The result of the executed block
     */
    suspend fun <T> recordTimed(block: suspend () -> T): T {
        var result: T
        val duration: Duration = measureTime {
            result = block()
        }
        processingTimer.record(duration.toJavaDuration())
        return result
    }
}
