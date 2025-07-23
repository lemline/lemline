// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.toJavaDuration


/**
 * Provides metrics for monitoring workflow message processing.
 * Uses Micrometer for metrics collection and reporting.
 */
@Singleton
class MessageSubscriberMetrics @Inject constructor(
    private val registry: MeterRegistry
) {
    /** Tracks the number of messages currently being processed */
    private val activeMessages = AtomicInteger(0)

    // Non-dimensional counters that don't need to be created on the fly.
    private val receivedCounter: Counter = registry.counter(METRIC_RECEIVED_TOTAL)
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

    /**
     * Increments the counter for successful deserialization.
     */
    fun deserializationCompleted(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_DESERIALIZATION_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for failed deserialization.
     */
    fun deserializationFailed(reason: String) = registry.counter(
        METRIC_DESERIALIZATION_FAILED_TOTAL,
        TAG_REASON, reason,
    ).increment()

    /**
     * Increments the counter for successfully processed messages.
     */
    fun processingCompleted(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_PROCESSING_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for failed message processing attempts.
     */
    fun processingFailed(reason: String, workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_PROCESSING_FAILED_TOTAL,
        TAG_REASON, reason,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for successfully acknowledged messages.
     */
    fun ackCompleted(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_ACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for failed acknowledgment attempts.
     */
    fun ackFailed(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_ACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for successfully unacknowledged messages.
     */
    fun nackCompleted(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_NACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /**
     * Increments the counter for failed negative-acknowledgment attempts.
     */
    fun nackFailed(workflowName: String, workflowVersion: String) = registry.counter(
        METRIC_NACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, workflowName,
        TAG_WORKFLOW_VERSION, workflowVersion
    ).increment()

    /** Increments the counter for processing saturation events. */
    fun saturated() = saturationCounter.increment()

    /** Returns the current count of active messages. */
    fun getActiveCount(): Int = activeMessages.get()

    /**
     * Records the duration of deserialization
     */
    suspend fun <T> recordDeserializationDuration(block: suspend () -> T): T {
        val timer = registry.timer(METRIC_DESERIALIZATION_DURATION)
        var result: T
        val duration: Duration = measureTime { result = block() }
        timer.record(duration.toJavaDuration())
        return result
    }

    /**
     * Records the duration of processing
     */
    suspend fun <T> recordProcessingDuration(workflowName: String, workflowVersion: String, block: suspend () -> T): T {
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

        private const val METRIC_DESERIALIZATION_COMPLETED_TOTAL = "$METRIC_PREFIX.deserialization.completed.total"
        private const val METRIC_DESERIALIZATION_FAILED_TOTAL = "$METRIC_PREFIX.deserialization.failed.total"
        private const val METRIC_DESERIALIZATION_DURATION = "$METRIC_PREFIX.deserialization.duration"

        private const val METRIC_PROCESSING_COMPLETED_TOTAL = "$METRIC_PREFIX.processing.completed.total"
        private const val METRIC_PROCESSING_FAILED_TOTAL = "$METRIC_PREFIX.processing.failed.total"
        private const val METRIC_PROCESSING_DURATION = "$METRIC_PREFIX.processing.duration"

        private const val METRIC_ACK_COMPLETED_TOTAL = "$METRIC_PREFIX.ack.completed.total"
        private const val METRIC_ACK_FAILED_TOTAL = "$METRIC_PREFIX.ack.failed.total"

        private const val METRIC_NACK_COMPLETED_TOTAL = "$METRIC_PREFIX.nack.completed.total"
        private const val METRIC_NACK_FAILED_TOTAL = "$METRIC_PREFIX.nack.failed.total"

        private const val METRIC_SATURATION_TOTAL = "$METRIC_PREFIX.parallelism.saturation.total"

        // Tag Keys
        private const val TAG_REASON = "reason"
        private const val TAG_WORKFLOW_NAME = "workflow_name"
        private const val TAG_WORKFLOW_VERSION = "workflow_version"

        // Tag Values for 'reason'
        object FailureReasons {
            const val DEFINITION_NOT_FOUND = "definition_not_found"
            const val SECRETS_RETRIEVAL_FAILED = "secrets_retrieval_failed"
            const val MESSAGE_EMISSION_ERROR = "message_emission_error"
            const val DATABASE_ERROR = "database_error"
            const val IO_ERROR = "io_error"
            const val INVALID_STATE = "invalid_state"
            const val PROCESSING_ERROR = "processing_error"
            const val WORKFLOW_ERROR_PREFIX = "workflow_"
        }
    }
}
