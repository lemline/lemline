// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.warn
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage

@ExperimentalTime
internal interface MessageHandler {
    suspend fun handleMessage(message: ReactiveMessage<String>)

    val logger: org.slf4j.Logger

    val metrics: MessageSubscriberMetrics

    /**
     * Acknowledges a reactive message to indicate successful processing.
     * If the acknowledgment fails, logs the error and increments the failure metrics counter.
     *
     * @param this The reactive message being acknowledged.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    suspend fun ReactiveMessage<*>.ack(workflowName: String, workflowVersion: String) {
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
    suspend fun ReactiveMessage<*>.nack(reason: Exception, workflowName: String, workflowVersion: String) {
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
    }
}
