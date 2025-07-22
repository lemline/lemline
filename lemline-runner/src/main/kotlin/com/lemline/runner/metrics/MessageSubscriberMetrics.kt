package com.lemline.runner.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
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
    private val registry: MeterRegistry
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

    /** Counts occurrences of processing saturation (max parallelism reached) */
    private val saturationCounter: Counter =
        registry.counter("lemline.message.parallelism.saturation.total")

    init {
        Gauge.builder("lemline.message.active", activeMessages) { it.toDouble() }
            .description("Current number of workflow messages being processed")
            .register(registry)
    }

    /** Increments the count of active messages. */
    fun incrementActive() {
        activeMessages.incrementAndGet()
    }

    /** Decrements the count of active messages. */
    fun decrementActive() {
        activeMessages.decrementAndGet()
    }

    /** Increments the counter for received messages. */
    fun received() = receivedCounter.increment()

    /** Increments the counter for acknowledged messages. */
    fun acknowledged() = acknowledgedCounter.increment()

    /** Increments the counter for unacknowledged messages. */
    fun unacknowledged() = unacknowledgedCounter.increment()

    /**
     * Increments the counter for successfully processed messages, tagged by workflow name and version.
     * @param workflowName The name of the workflow that was processed.
     * @param workflowVersion The version of the workflow that was processed.
     */
    fun processed(workflowName: String, workflowVersion: String) {
        registry.counter(
            "lemline.message.processed.total",
            "workflow_name", workflowName,
            "workflow_version", workflowVersion
        ).increment()
    }

    /**
     * Increments the counter for failed message processing attempts, tagged by a reason.
     * @param reason A short, descriptive reason for the failure (e.g., 'deserialization', 'processing').
     * @param workflowName The name of the workflow that failed, if known.
     * @param workflowVersion The version of the workflow that was processed.
     */
    fun failed(reason: String, workflowName: String, workflowVersion: String) {
        registry.counter(
            "lemline.message.failed.total",
            "reason", reason,
            "workflow_name", workflowName,
            "workflow_version", workflowVersion
        ).increment()
    }

    /** Increments the counter for processing saturation events. */
    fun saturated() = saturationCounter.increment()

    /** Returns the current count of active messages. */
    fun getActiveCount(): Int = activeMessages.get()

    /**
     * Records the duration of a suspending block of code, tagged by workflow name.
     *
     * @param workflowName The name of the workflow being processed.
     * @param block The suspending code block to measure.
     * @return The result of the executed block.
     */
    suspend fun <T> recordTimed(workflowName: String, workflowVersion: String, block: suspend () -> T): T {
        val timer = registry.timer(
            "lemline.message.processing.duration",
            "workflow_name", workflowName,
            "workflow_version", workflowVersion
        )
        var result: T
        val duration: Duration = measureTime {
            result = block()
        }
        timer.record(duration.toJavaDuration())
        return result
    }
}
