// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.LogContext
import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.ids.IdGenerator
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.common.withLoggingContext
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.models.FailureModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@ExperimentalTime
@ApplicationScoped
@ExperimentalSerializationApi
internal class IngestionMessageHandler(
    private val parentRepository: ParentRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    private val metrics: IngestionMessageSubscriberMetrics,
) : MessageHandler {

    val logger = logger()

    /**
     * Handles the entire lifecycle of an incoming reactive message. This includes deserialization,
     * processing, emission of the next step, and finally acknowledgment (ack/nack).
     *
     * This function is designed to be resilient and will not throw exceptions, instead handling
     * failures by logging, recording metrics, and saving messages for retry or inspection.
     *
     * @param message The raw reactive message from the messaging system.
     */
    override suspend fun handleMessage(message: Message<String>) {
        // --- Deserialization ---
        val ingestionMessage = message.deserialize() ?: return

        try {
            with(ingestionMessage) {
                metrics.recordProcessingDuration(workflowName ?: UNKNOWN, workflowVersion ?: UNKNOWN) {
                    withLoggingContext(
                        LogContext.WORKFLOW_ID to workflowId.toString(),
                        LogContext.WORKFLOW_NAME to workflowName,
                        LogContext.WORKFLOW_VERSION to workflowVersion,
                        LogContext.NODE_POSITION to (workflowPosition?.serialized ?: UNKNOWN),
                    ) {
                        when (ingestionMessage) {
                            is ParentIngestionMessage -> ingestionMessage.save()
                            is RetryIngestionMessage -> ingestionMessage.save()
                            is ScheduleIngestionMessage -> ingestionMessage.save()
                            is WaitIngestionMessage -> ingestionMessage.save()
                            is FailureIngestionMessage -> ingestionMessage.save()
                        }
                    }
                    metrics.processingCompleted(workflowName ?: UNKNOWN, workflowVersion ?: UNKNOWN)
                }
            }
        } catch (_: ProcessingException) {
            // Nothing to do
        }
    }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */

    private suspend fun Message<String>.deserialize(): IngestionMessage? = try {
        metrics.recordDeserializationDuration {
            IngestionMessage.fromJsonString(payload)
        }.also {
            metrics.deserializationCompleted(it.workflowName ?: UNKNOWN, it.workflowVersion ?: UNKNOWN)
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to deserialize message: $payload" }
        metrics.deserializationFailed(e)
        // Deserialization failure is fatal for this message. Save and ACK.
        deserializationFailed(e)
        null
    }

    private suspend fun ParentIngestionMessage.save() {
        parentRepository.insert(toModel())
    }

    private suspend fun RetryIngestionMessage.save() {
        retryRepository.insert(toModel())
    }

    private suspend fun ScheduleIngestionMessage.save() {
        scheduleRepository.insert(toModel())
    }

    private suspend fun WaitIngestionMessage.save() {
        waitRepository.insert(toModel())
    }

    private suspend fun FailureIngestionMessage.save() {
        failureRepository.insert(toModel())
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) = try {
        val failure = FailureModel.from(
            id = IdGenerator.generateV7(),
            payload = payload,
            reason = FailureReasons.DESERIALISATION_ERROR,
            error = cause
        )
        failureRepository.insert(failure)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(UNKNOWN, UNKNOWN)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: ${this.payload}" }
        nack(e, UNKNOWN, UNKNOWN)
    }

    /**
     * Acknowledges a reactive message to indicate successful processing.
     * If the acknowledgment fails, logs the error and increments the failure metrics counter.
     *
     * @param this The reactive message being acknowledged.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    private fun Message<*>.ack(workflowName: String, workflowVersion: String) {
        try {
            logger.debug { "ACKing message: $payload" }
            ack()
            metrics.ackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.error(e) { "CRITICAL: Failed to ACK message. Duplicate processing may occur: $payload" }
            metrics.ackFailed(workflowName, workflowVersion)
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
    private fun Message<*>.nack(reason: Exception, workflowName: String, workflowVersion: String) {
        try {
            logger.debug { "NACKing message: $payload" }
            nack(reason)
            metrics.nackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to NACK message. This message should be represented by brokers: $payload" }
            metrics.nackFailed(workflowName, workflowVersion)
        }
    }

    internal class ProcessingException(cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val UNKNOWN = "unknown"
    }
}
