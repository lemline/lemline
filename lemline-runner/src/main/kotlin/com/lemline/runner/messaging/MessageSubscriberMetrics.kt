// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.core.errors.WorkflowException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource.Monotonic.markNow
import kotlin.time.toJavaDuration

/**
 * Provides metrics for monitoring workflow message processing.
 * Uses Micrometer for metrics collection and reporting.
 */
@Suppress("PropertyName")
@ExperimentalTime
internal abstract class MessageSubscriberMetrics(val registry: MeterRegistry) {

    // Metric Names
    protected abstract val METRIC_PREFIX: String
    protected val METRIC_ACTIVE = "$METRIC_PREFIX.active"
    protected val METRIC_RECEIVED_TOTAL = "$METRIC_PREFIX.received.total"

    protected val METRIC_DESERIALIZATION_COMPLETED_TOTAL = "$METRIC_PREFIX.deserialization.completed.total"
    protected val METRIC_DESERIALIZATION_FAILED_TOTAL = "$METRIC_PREFIX.deserialization.failed.total"
    protected val METRIC_DESERIALIZATION_DURATION = "$METRIC_PREFIX.deserialization.duration"

    protected val METRIC_PROCESSING_COMPLETED_TOTAL = "$METRIC_PREFIX.processing.completed.total"
    protected val METRIC_PROCESSING_FAILED_TOTAL = "$METRIC_PREFIX.processing.failed.total"
    protected val METRIC_PROCESSING_DURATION = "$METRIC_PREFIX.processing.duration"

    protected val METRIC_ACK_COMPLETED_TOTAL = "$METRIC_PREFIX.ack.completed.total"
    protected val METRIC_ACK_FAILED_TOTAL = "$METRIC_PREFIX.ack.failed.total"

    protected val METRIC_NACK_COMPLETED_TOTAL = "$METRIC_PREFIX.nack.completed.total"
    protected val METRIC_NACK_FAILED_TOTAL = "$METRIC_PREFIX.nack.failed.total"

    /** Tracks the number of messages currently being processed */
    private val activeMessages = AtomicInteger(0)

    // Non-dimensional counters that don't need to be created on the fly.
    private val receivedCounter: Counter = registry.counter(METRIC_RECEIVED_TOTAL)

    init {
        Gauge.builder(METRIC_ACTIVE, activeMessages) { it.toDouble() }
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
    fun deserializationFailed(e: Exception) = registry.counter(
        METRIC_DESERIALIZATION_FAILED_TOTAL,
        TAG_REASON, getFailureReason(e),
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

    fun processingFailed(e: Exception, workflowName: String, workflowVersion: String) =
        processingFailed(getFailureReason(e), workflowName, workflowVersion)

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

    /** Returns the current count of active messages. */
    fun getActiveCount(): Int = activeMessages.get()

    /**
     * Records the duration of deserialization
     */
    suspend fun <T> recordDeserializationDuration(block: suspend () -> T): T {
        val timer = registry.timer(METRIC_DESERIALIZATION_DURATION)
        val start = markNow()
        return try {
            block()
        } finally {
            // Always record the duration, even if the block throws an exception.
            timer.record(start.elapsedNow().toJavaDuration())
        }
    }

    /**
     * Records the duration of processing
     */
    suspend fun <T> recordProcessingDuration(workflowName: String, workflowVersion: String, block: suspend () -> T): T {
        val timer = registry.timer(
            METRIC_PROCESSING_DURATION,
            TAG_WORKFLOW_NAME,
            workflowName,
            TAG_WORKFLOW_VERSION,
            workflowVersion
        )
        val start = markNow()
        return try {
            block()
        } finally {
            // Always record the duration, even if the block throws an exception.
            timer.record(start.elapsedNow().toJavaDuration())
        }
    }


    companion object {
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

        /**
         * Determines a low-cardinality failure reason from an exception for use in metrics.
         * This is crucial for creating actionable alerts and dashboards without overwhelming
         * the metrics backend.
         */
        fun getFailureReason(e: Throwable): String = when (e) {
            // Domain-specific errors from the workflow engine
            is WorkflowException -> FailureReasons.WORKFLOW_ERROR_PREFIX + e.error.type.lowercase()

            // --- Database & Persistence Errors ---
            is SQLException -> FailureReasons.DATABASE_ERROR

            // --- I/O and Network Errors ---
            is IOException -> FailureReasons.IO_ERROR

            // --- Application State Errors ---
            is IllegalStateException -> FailureReasons.INVALID_STATE

            // --- Fallback for any other uncategorized exception ---
            else -> FailureReasons.PROCESSING_ERROR
        }
    }
}
