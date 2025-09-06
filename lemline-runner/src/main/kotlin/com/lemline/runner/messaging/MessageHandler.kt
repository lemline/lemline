// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.core.logger.Logger
import com.lemline.core.logger.withSuspendLoggingContext
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.failures.FailureReasons.getFailureReason
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
internal interface MessageHandler<T : WorkflowMessage> {

    suspend fun Message<String>.deserialize(): T

    suspend fun T.handle(): T?

    suspend fun T.emit()

    val logger: Logger

    val metrics: MessageSubscriberMetrics

    val onCompleteTest: (Message<String>, T?) -> Unit

    val onFailureTest: (Message<String>, Throwable?) -> Unit

    suspend fun handleMessage(message: Message<String>) {
        var next: T?
        var workflowId: WorkflowId? = null
        var workflowName: WorkflowName? = null
        var workflowVersion: WorkflowVersion? = null

        // --- Deserialization ---
        val msg: T = message.tryWithCompensation {
            logger.debug { "Received: ${message.toLogString()}" }
            metrics.recordDeserializationDuration {
                try {
                    message.deserialize()
                } catch (e: Exception) {
                    metrics.deserializationFailed(e)
                    throw e
                }.also {
                    // deserialisation succeeded
                    logger.debug { "Deserialized ${message.toLogString()}: $it" }
                    workflowId = it.workflowId
                    workflowName = it.workflowName
                    workflowVersion = it.workflowVersion
                    metrics.deserializationCompleted(workflowName, workflowVersion)
                }
            }
        } ?: return // <- deserialization() failed

        // --- Processing ---
        message.tryWithCompensation(workflowId, workflowName, workflowVersion) {
            // Process et get next message
            next = metrics.recordProcessingDuration(workflowName, workflowVersion) {
                try {
                    msg.handle()
                } catch (e: Exception) {
                    val reason = if (e is CompensationException) e.reason else getFailureReason(e)
                    metrics.processingFailed(reason, workflowName, workflowVersion)
                    throw e
                }.also {
                    logger.debug { "Processed: ${message.toLogString()}" }
                    metrics.processingCompleted(workflowName, workflowVersion)
                }
            }
            // Serialize and emit the next message if any
            next?.emit()
        } ?: return // <- handle() failed

        // Success Path
        message.acknowledgeWithRetry(workflowName, workflowVersion)
    }

    /**
     * Attempts to execute a suspending block within a log context.
     * If failing,
     * - the compensation actions of the CompensationException are executed, and the message is acknowledged.
     * - if failing again, the message is negatively acknowledged.
     *
     * @return The result of the block if successful, else null
     *
     * @throws Exception If an error occurs during (negative) acknowledgment
     */
    suspend fun <T> Message<String>.tryWithCompensation(
        workflowId: WorkflowId? = null,
        workflowName: WorkflowName? = null,
        workflowVersion: WorkflowVersion? = null,
        block: suspend () -> T
    ): T? = withSuspendLoggingContext(workflowId, workflowName, workflowVersion) {
        try {
            block()
        } catch (compensation: CompensationException) {
            try {
                compensation.run()
                acknowledgeWithRetry(workflowName, workflowVersion)
            } catch (e: Exception) {
                // Failure path
                negAcknowledgeWithRetry(e, workflowName, workflowVersion)
                onFailureTest(this, e)
            }
            null
        } catch (e: Exception) {
            // Failure path
            negAcknowledgeWithRetry(e, workflowName, workflowVersion)
            onFailureTest(this, e)
            null
        }
    }

    /**
     * Acknowledges the current message with retry logic.
     *
     * If the acknowledgment fails, an exception is thrown to trigger a broker reconnection
     */
    suspend fun Message<String>.acknowledgeWithRetry(
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?
    ) = try {
        with(AckNackPolicy) { ackWithRetry() }
        logger.debug { "Message ACKed: ${toLogString()}" }
        metrics.ackCompleted(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to ACK message: ${toLogString()}" }
        metrics.ackFailed(workflowName, workflowVersion)
        throw e
    }

    /**
     * Handles the process of negatively acknowledging a message with retry logic.
     * If the retry attempts fail, the message is quarantined locally, and an acknowledgment
     * is attempted to ensure the message is processed within the defined constraints.
     *
     * If quarantining locally or the acknowledgment fails, an exception is thrown to trigger a broker reconnection
     */
    suspend fun Message<String>.negAcknowledgeWithRetry(
        e: Exception,
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?
    ) = try {
        with(AckNackPolicy) { nackWithRetry(e) }
        logger.warn(e) { "Message NACKed: ${toLogString()} - should be sent to the DLQ by brokers" }
        metrics.nackCompleted(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to NACK message: ${toLogString()}" }
        metrics.nackFailed(workflowName, workflowVersion)
        throw e
    }

    companion object {
        private const val UNKNOWN = ""
        val UNKNOWN_NAME = WorkflowName(UNKNOWN)
        val UNKNOWN_VERSION = WorkflowVersion(UNKNOWN)
    }
}

class CompensationException(val reason: String, val run: suspend () -> Unit) : RuntimeException()

