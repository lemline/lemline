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

    // Non-dimensional counters that don't need to be created on the fly.
    private val receivedCounter: Counter = registry.counter(METRIC_RECEIVED_TOTAL)
    private val acknowledgementCompletedCounter: Counter = registry.counter(METRIC_ACK_COMPLETED_TOTAL)
    private val acknowledgementFailedCounter: Counter = registry.counter(METRIC_ACK_FAILED_TOTAL)
    private val unAcknowledgementCompletedCounter: Counter = registry.counter(METRIC_NACK_COMPLETED_TOTAL)
    private val unAcknowledgementFailedCounter: Counter = registry.counter(METRIC_NACK_FAILED_TOTAL)
    private val saturationCounter: Counter = registry.counter(METRIC_SATURATION_TOTAL)


    init {
        Gauge.builder(METRIC_ACTIVE_MESSAGES, activeMessages) { it.toDouble() }
            .description("Current number of messages being processed")
            .register(registry)
    }

    /** Increments the count of active messages. */
    fun incrementActive() = activeMessages.incrementAndGet()

    /** Decrements the count of active messages. */
    fun decrementActive() = activeMessages.decrementAndGet()

    /** Increments the counter for received messages. */
    fun received() = receivedCounter.increment()

    /** Increments the counter for acknowledged messages. */
    fun acknowledgementCompleted() = acknowledgementCompletedCounter.increment()

    /** Increments the counter for unacknowledged messages. */
    fun unAcknowledgementCompleted() = unAcknowledgementCompletedCounter.increment()

    /** Increments the counter for failed acknowledgment attempts. */
    fun acknowledgementFailed() = acknowledgementFailedCounter.increment()

    /** Increments the counter for failed negative-acknowledgment attempts. */
    fun unAcknowledgementFailed() = unAcknowledgementFailedCounter.increment()

    /**
     * Increments the counter for successfully processed messages, tagged by workflow name and version.
     * @param workflowName The name of the workflow that was processed.
     * @param workflowVersion The version of the workflow that was processed.
     */
    fun processingCompleted(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_PROCESSING_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for failed message processing attempts, tagged by a reason.
     * @param reason A short, descriptive reason for the failure (e.g., 'deserialization', 'processing').
     * @param workflowName The name of the workflow that failed, if known.
     * @param workflowVersion The version of the workflow that was processed.
     */
    fun processingFailed(reason: String, workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_PROCESSING_FAILED_TOTAL,
        TAG_REASON, reason,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

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
            METRIC_PROCESSING_DURATION,
            TAG_WORKFLOW_NAME, workflowName,
            TAG_WORKFLOW_VERSION, workflowVersion
        )
        var result: T
        val duration: Duration = measureTime {
            result = block()
        }
        timer.record(duration.toJavaDuration())
        return result
    }

    companion object {
        // Metric Names
        private const val METRIC_PREFIX = "lemline.message"
        private const val METRIC_ACTIVE_MESSAGES = "$METRIC_PREFIX.active"
        private const val METRIC_RECEIVED_TOTAL = "$METRIC_PREFIX.received.total"
        private const val METRIC_PROCESSING_COMPLETED_TOTAL = "$METRIC_PREFIX.processing.completed.total"
        private const val METRIC_PROCESSING_FAILED_TOTAL = "$METRIC_PREFIX.processing.failed.total"
        private const val METRIC_ACK_COMPLETED_TOTAL = "$METRIC_PREFIX.ack.completed.total"
        private const val METRIC_ACK_FAILED_TOTAL = "$METRIC_PREFIX.ack.failed.total"
        private const val METRIC_NACK_COMPLETED_TOTAL = "$METRIC_PREFIX.nack.completed.total"
        private const val METRIC_NACK_FAILED_TOTAL = "$METRIC_PREFIX.nack.failed.total"
        private const val METRIC_SATURATION_TOTAL = "$METRIC_PREFIX.parallelism.saturation.total"
        private const val METRIC_PROCESSING_DURATION = "$METRIC_PREFIX.processing.duration"

        // Tag Keys
        private const val TAG_REASON = "reason"
        private const val TAG_WORKFLOW_NAME = "workflow_name"
        private const val TAG_WORKFLOW_VERSION = "workflow_version"
    }
}
