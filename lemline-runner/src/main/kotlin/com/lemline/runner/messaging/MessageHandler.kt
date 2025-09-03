// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.warn
import com.lemline.core.logger.withWorkflowContext
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.models.WithInstance
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage
import org.slf4j.Logger

@ExperimentalTime
internal interface MessageHandler<T : WithInstance> {

    suspend fun Message<String>.deserialize(): T

    suspend fun T.handle(): T?

    suspend fun T.emit()

    val logger: Logger

    val metrics: MessageSubscriberMetrics

    val onComplete: (Message<String>, String?) -> Unit

    val onFailure: (Message<String>, Throwable?) -> Unit

    suspend fun handleMessage(message: Message<String>) {
        try {
            logger.debug { "Received: ${message.toLogString()}" }

            // --- Deserialization ---
            val msg = message.deserialize()
            logger.debug { "Deserialized: ${message.toLogString()}" }

            with(msg) {
                withWorkflowContext(workflowId, workflowName, workflowVersion, currentPosition) {
                    metrics.recordProcessingDuration(workflowName, version) {
                        // --- Processing ---
                        val next = msg.handle()
                        // --- Emit next message if any ---
                        next?.emit()
                        // --- Acknowledgment (Success Path) ---
                        message.ack(name, version)
                        logger.debug { "Processed: ${message.toLogString()}" }
                        // For testing
                        onComplete(message, next?.toJsonString())
                    }
                    metrics.processingCompleted(name, version)
                }
            }
        } catch (e: DelegatedException) {
            // For testing
            onFailure(message, e)
        }
    }

    /**
     * Acknowledges a reactive message to indicate successful processing.
     * If the acknowledgment fails, logs the error and increments the failure metrics counter.
     *
     * @param this The reactive message being acknowledged.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    suspend fun ReactiveMessage<*>.ack(workflowName: WorkflowName?, workflowVersion: WorkflowVersion?) {
        try {
            logger.debug { "Acknowledging message ${toLogString()}" }
            ackSuspending()
            metrics.ackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.info(e) { "Failed to acknowledge message. Trying now to NegAck it: ${toLogString()}" }
            metrics.ackFailed(workflowName, workflowVersion)
            nack(e, workflowName, workflowVersion)
        }
    }

    /**
     * Handles the negative acknowledgment (NACK) for a reactive message, including logging,
     * metrics increment, and exception handling if the NACK operation fails.
     *
     * @param this The reactive message to be negatively acknowledged.
     * @param reason The exception that triggered the negative acknowledgment.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    suspend fun ReactiveMessage<*>.nack(
        reason: Exception,
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?
    ) {
        try {
            nackSuspending(reason)
            logger.warn(reason) { "CRITICAL - Negatively Acknowledged message: ${toLogString()}" }
            metrics.nackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.error(e) { "CRITICAL - Failed to NACK message. This message should be represented by brokers: ${toLogString()}" }
            metrics.nackFailed(workflowName, workflowVersion)
            throw e
        }
    }

    companion object {
        const val UNKNOWN = "unknown"
        val UNKNOWN_NAME = WorkflowName(UNKNOWN)
        val UNKNOWN_VERSION = WorkflowVersion(UNKNOWN)
    }
}

class DelegatedException(block: suspend () -> Unit) : RuntimeException()

