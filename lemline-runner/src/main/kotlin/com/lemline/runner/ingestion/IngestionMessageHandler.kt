// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.logger
import com.lemline.core.logger.withWorkflowContext
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.MessageHandler.Companion.UNKNOWN_NAME
import com.lemline.runner.messaging.MessageHandler.Companion.UNKNOWN_VERSION
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.IDV7
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
    override val metrics: IngestionMessageSubscriberMetrics,
) : MessageHandler<IngestionMessage> {

    override val logger = logger()

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
        logger.debug { "Received: ${message.toLogString()}" }

        // --- Deserialization ---
        val ingestionMessage = message.deserialize() ?: return

        logger.debug { "Deserialized: ${message.toLogString()}" }

        try {
            with(ingestionMessage) {
                metrics.recordProcessingDuration(workflowName, workflowVersion) {
                    withWorkflowContext(workflowId, workflowName, workflowVersion, currentPosition) {
                        when (ingestionMessage) {
                            is ParentIngestionMessage -> ingestionMessage.save()
                            is RetryIngestionMessage -> ingestionMessage.save()
                            is ScheduleIngestionMessage -> ingestionMessage.save()
                            is WaitIngestionMessage -> ingestionMessage.save()
                            is FailureIngestionMessage -> ingestionMessage.save()
                        }
                        // --- Acknowledgment (Success Path) ---
                        message.ack(workflowName, workflowVersion)
                    }
                    metrics.processingCompleted(workflowName, workflowVersion)
                }
            }
            logger.debug { "Processed: ${message.toLogString()}" }
        } catch (e: Exception) {
            // --- NegAcknowledgment (Failure Path) ---
            message.nack(e, ingestionMessage.workflowName, ingestionMessage.workflowVersion)
            logger.error(e) { "Error during processing of message: ${message.toLogString()}" }

            throw e
        }
    }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */

    override suspend fun Message<String>.deserialize(): IngestionMessage = try {
        metrics.recordDeserializationDuration {
            IngestionMessage.fromJsonString(payload)
        }.also {
            metrics.deserializationCompleted(it.workflowName, it.workflowVersion)
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to deserialize message: ${toLogString()}" }
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
            id = IDV7.new(),
            payload = payload,
            reason = FailureReasons.DESERIALISATION_ERROR,
            error = cause
        )
        failureRepository.insert(failure)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(UNKNOWN_NAME, UNKNOWN_VERSION)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: ${toLogString()}" }
        nack(e, UNKNOWN_NAME, UNKNOWN_VERSION)
    }
}
