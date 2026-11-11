// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import com.lemline.common.values.WorkflowInfo
import com.lemline.runner.common.messaging.MessageHandler.Companion.UNKNOWN_NAME
import com.lemline.runner.common.messaging.MessageHandler.Companion.UNKNOWN_VERSION
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource.Monotonic.markNow
import kotlin.time.toJavaDuration

/**
 * Provides metrics for monitoring workflow message processing.
 * Uses Micrometer for metrics collection and reporting.
 */
@Suppress("PropertyName")
abstract class MessageSubscriberMetrics(
    val registry: MeterRegistry,
    private val metricPrefix: String,
) {

    // Metric Names
    protected val METRIC_ACTIVE = "$metricPrefix.active"
    protected val METRIC_RECEIVED_TOTAL = "$metricPrefix.received.total"

    protected val METRIC_DESERIALIZATION_COMPLETED_TOTAL = "$metricPrefix.deserialization.completed.total"
    protected val METRIC_DESERIALIZATION_FAILED_TOTAL = "$metricPrefix.deserialization.failed.total"
    protected val METRIC_DESERIALIZATION_DURATION = "$metricPrefix.deserialization.duration"

    protected val METRIC_SERIALIZATION_COMPLETED_TOTAL = "$metricPrefix.serialization.completed.total"
    protected val METRIC_SERIALIZATION_FAILED_TOTAL = "$metricPrefix.serialization.failed.total"
    protected val METRIC_SERIALIZATION_DURATION = "$metricPrefix.serialization.duration"

    protected val METRIC_PROCESSING_COMPLETED_TOTAL = "$metricPrefix.processing.completed.total"
    protected val METRIC_PROCESSING_FAILED_TOTAL = "$metricPrefix.processing.failed.total"
    protected val METRIC_PROCESSING_DURATION = "$metricPrefix.processing.duration"

    protected val METRIC_ACK_COMPLETED_TOTAL = "$metricPrefix.ack.completed.total"
    protected val METRIC_ACK_FAILED_TOTAL = "$metricPrefix.ack.failed.total"

    protected val METRIC_NACK_COMPLETED_TOTAL = "$metricPrefix.nack.completed.total"
    protected val METRIC_NACK_FAILED_TOTAL = "$metricPrefix.nack.failed.total"

    protected val METRIC_FOREIGN_NAMESPACE_TOTAL = "$metricPrefix.foreign_namespace.total"

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
    fun deserializationCompleted(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_DESERIALIZATION_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed deserialization.
     */
    fun deserializationFailed(e: Exception) = registry.counter(
        METRIC_DESERIALIZATION_FAILED_TOTAL,
        TAG_REASON, getFailureReason(e),
    ).increment()

    /**
     * Increments the counter for failed serialization.
     */
    fun serializationFailed(workflowInfo: WorkflowInfo?, e: Exception) = registry.counter(
        METRIC_SERIALIZATION_FAILED_TOTAL,
        TAG_REASON, getFailureReason(e),
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for successful serialization.
     */
    fun serializationCompleted(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_SERIALIZATION_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()


    /**
     * Increments the counter for successfully processed messages.
     */
    fun processingCompleted(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_PROCESSING_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed message processing attempts.
     */
    fun processingFailed(reason: String, workflowInfo: WorkflowInfo?) =
        registry.counter(
            METRIC_PROCESSING_FAILED_TOTAL,
            TAG_REASON, reason,
            TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
            TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
        ).increment()

    /**
     * Increments the counter for messages delivered on this channel whose namespace is not
     * followed by this runner — a broker binding/config anomaly, never a nominal-path event.
     * Cardinality is bounded by the namespaces actually seen on misrouted messages.
     */
    fun foreignNamespace(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_FOREIGN_NAMESPACE_TOTAL,
        TAG_WORKFLOW_NAMESPACE, workflowInfo?.namespace?.toString() ?: "",
    ).increment()

    /**
     * Increments the counter for successfully acknowledged messages.
     */
    fun ackCompleted(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_ACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed acknowledgment attempts.
     */
    fun ackFailed(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_ACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for successfully unacknowledged messages.
     */
    fun nackCompleted(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_NACK_COMPLETED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
    ).increment()

    /**
     * Increments the counter for failed negative-acknowledgment attempts.
     */
    fun nackFailed(workflowInfo: WorkflowInfo?) = registry.counter(
        METRIC_NACK_FAILED_TOTAL,
        TAG_WORKFLOW_NAME, (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
        TAG_WORKFLOW_VERSION, (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
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
            block().also {
                deserializationCompleted(it as? WorkflowInfo)
            }
        } catch (e: Exception) {
            deserializationFailed(e)
            throw e
        } finally {
            // Always record the duration, even if the block throws an exception.
            timer.record(start.elapsedNow().toJavaDuration())
        }
    }

    /**
     * Records the duration of serialization
     */
    suspend fun <T> recordSerializationDuration(current: T, block: suspend () -> String): String {
        val timer = registry.timer(METRIC_SERIALIZATION_DURATION)
        val start = markNow()
        return try {
            block().also {
                serializationCompleted(current as? WorkflowInfo)
            }
        } catch (e: Exception) {
            serializationFailed(current as? WorkflowInfo, e)
            throw e
        } finally {
            // Always record the duration, even if the block throws an exception.
            timer.record(start.elapsedNow().toJavaDuration())
        }
    }

    /**
     * Records the duration of processing
     */
    suspend fun <T> recordProcessingDuration(workflowInfo: WorkflowInfo?, block: suspend () -> T): T {
        val timer = registry.timer(
            METRIC_PROCESSING_DURATION,
            TAG_WORKFLOW_NAME,
            (workflowInfo?.name ?: UNKNOWN_NAME).toString(),
            TAG_WORKFLOW_VERSION,
            (workflowInfo?.version ?: UNKNOWN_VERSION).toString()
        )
        val start = markNow()
        return try {
            block().also {
                processingCompleted(workflowInfo)
            }
        } catch (e: Exception) {
            val reason = if (e is CompensationException) e.reason else getFailureReason(e)
            processingFailed(reason, workflowInfo)
            throw e
        } finally {
            // Always record the duration, even if the block throws an exception.
            timer.record(start.elapsedNow().toJavaDuration())
        }
    }

    protected fun getFailureReason(e: Throwable): String = FailureReasons.getFailureReason(e)

    companion object {
        // Tag Keys
        private const val TAG_REASON = "reason"
        private const val TAG_WORKFLOW_NAME = "workflow_name"
        private const val TAG_WORKFLOW_VERSION = "workflow_version"
        private const val TAG_WORKFLOW_NAMESPACE = "workflow_namespace"
    }
}
