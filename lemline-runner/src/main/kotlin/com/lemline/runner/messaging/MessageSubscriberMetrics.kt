// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.MessageHandler.Companion.UNKNOWN_NAME
import com.lemline.runner.messaging.MessageHandler.Companion.UNKNOWN_VERSION
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
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
    fun deserializationCompleted(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_DESERIALIZATION_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
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
    fun processingCompleted(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_PROCESSING_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed message processing attempts.
     */
    fun processingFailed(reason: String, workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) =
        registry.counter(
            METRIC_PROCESSING_FAILED_TOTAL,
            TAG_REASON, reason,
            TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
            TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
        ).increment()

    fun processingFailed(e: Exception, workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) =
        processingFailed(getFailureReason(e), workflowName, workflowVersion)

    /**
     * Increments the counter for successfully acknowledged messages.
     */
    fun ackCompleted(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_ACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed acknowledgment attempts.
     */
    fun ackFailed(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_ACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for successfully unacknowledged messages.
     */
    fun nackCompleted(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_NACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed negative-acknowledgment attempts.
     */
    fun nackFailed(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) = registry.counter(
        METRIC_NACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowName ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowVersion ?: UNKNOWN_VERSION).toString()
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
    suspend fun <T> recordProcessingDuration(
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?,
        block: suspend () -> T
    ): T {
        val timer = registry.timer(
            METRIC_PROCESSING_DURATION,
            TAG_WORKFLOW_NAME,
            (workflowName ?: UNKNOWN_NAME).toString(),
            TAG_WORKFLOW_VERSION,
            (workflowVersion ?: UNKNOWN_VERSION).toString()
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
    }
}
