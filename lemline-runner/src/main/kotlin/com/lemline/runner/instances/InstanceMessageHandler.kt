// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.LogContext
import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.common.withLoggingContext
import com.lemline.core.definitions.Definitions
import com.lemline.core.processor.Processor
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.MessageSubscriberMetrics.Companion.FailureReasons
import com.lemline.runner.models.RETRY_TABLE
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.secrets.Secrets
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class InstanceMessageHandler(
    private val emitter: InstanceMessageEmitter,
    private val definitionRepository: DefinitionRepository,
    private val retryRepository: RetryRepository,
    private val stepByStepRunner: StepByStepRunner,
    private val metrics: InstanceMessageSubscriberMetrics,
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
        val instanceMessage = message.deserialize() ?: return

        try {
            with(instanceMessage) {
                metrics.recordProcessingDuration(workflowName, workflowVersion) {
                    withLoggingContext(
                        LogContext.WORKFLOW_ID to workflowId.toString(),
                        LogContext.WORKFLOW_NAME to workflowName,
                        LogContext.WORKFLOW_VERSION to workflowVersion,
                        LogContext.NODE_POSITION to workflowPosition.serialized,
                    ) {
                        // --- Get Workflow Definition ---
                        val workflow = findWorkflowDefinition()
                        // --- Get secrets for this workflow ---
                        val secrets = findSecrets(workflow)
                        // --- Get an instance of this workflow ---
                        val workflowInstance = getWorkflowProcessor(secrets)
                        // --- Run this instance ---
                        val nextMessage = run(workflowInstance)
                        // --- Emit next message if any ---
                        nextMessage?.emit()
                        // --- Acknowledgment (Success Path) ---
                        message.ack(workflowName, workflowVersion)
                        // For testing: complete the future

                        processingMessages.remove(message.payload)?.complete(nextMessage?.toJsonString())
                    }
                    metrics.processingCompleted(workflowName, workflowVersion)
                }
            }
        } catch (e: ProcessingException) {
            onProcessingFailure(instanceMessage, e)
            // For testing: complete the future
            processingMessages.remove(message.payload)?.completeExceptionally(e.cause)
        }
    }

    var onProcessingFailure = { _: InstanceMessage, _: Exception -> }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */
    private suspend fun Message<String>.deserialize(): InstanceMessage? = try {
        metrics.recordDeserializationDuration {
            InstanceMessage.fromMessage(this)
        }.also {
            metrics.deserializationCompleted(it.workflowName, it.workflowVersion)
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to deserialize message: $payload" }
        metrics.deserializationFailed(e)
        // Deserialization failure is fatal for this message. Save and ACK.
        deserializationFailed(e)
        null
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     * If the workflow is still not found,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow = try {
        // Try to get from cache first, then from repository if not found
        Definitions.getOrNull(workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowName, workflowVersion)
                ?.definition
                ?.let { Definitions.parseAndPut(it) }
    } catch (e: Exception) {
        logger.error(e) { "Error during workflow definition retrieval" }
        metrics.processingFailed(e, workflowName, workflowVersion)
        saveForRetry(e)
        throw ProcessingException(e)
    } ?: run {
        // If `workflow` is null
        metrics.processingFailed(FailureReasons.DEFINITION_NOT_FOUND, workflowName, workflowVersion)
        val cause = IllegalStateException("Workflow ${workflowName}:${workflowVersion} not found")
        saveAsFailed(cause)
        throw ProcessingException(cause)
    }

    /**
     * Retrieves the necessary secrets for a workflow and handles any errors that occur during the process.
     *
     * If an error occurs while getting the secrets, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.findSecrets(
        workflow: Workflow,
    ): Map<String, JsonElement> = try {
        Secrets.getForWorkflow(workflow)
    } catch (e: Exception) {
        logger.error(e) { "Unable to retrieve needed secret. Storing the message in the $RETRY_TABLE table for manual inspection." }
        metrics.processingFailed(FailureReasons.SECRETS_RETRIEVAL_FAILED, workflowName, workflowVersion)
        saveAsFailed(e)
        throw ProcessingException(e)
    }

    /**
     * Constructs a workflow instance based on the provided message and secrets.
     *
     * If an error occurs while constructing the instance, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.getWorkflowProcessor(
        secrets: Map<String, JsonElement>
    ): Processor = try {
        Processor(instance = this, secrets = secrets)
    } catch (e: Exception) {
        logger.error(e) { "Failed to convert the message to a workflow processor. Storing it in the $RETRY_TABLE table for manual inspection." }
        metrics.processingFailed(e, workflowName, workflowVersion)
        saveAsFailed(e)
        throw ProcessingException(e)
    }

    /**
     * Executes a workflow instance constructed from the provided message, state, and secrets.
     *
     * This method attempts to run the workflow instance step by step using the `stepByStepRunner`.
     * If execution fails, it logs the failure, updates processing metrics, and stores the message
     * in the retry table for manual inspection.
     */
    private suspend fun run(processor: Processor): InstanceMessage? {
        return try {
            with(stepByStepRunner) { run(processor) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to run instance. Storing current state in the $RETRY_TABLE table for manual inspection." }
            metrics.processingFailed(e, processor.instance.name, processor.instance.version)
            processor.runFailed(e)
            throw ProcessingException(e)
        }
    }

    /**
     * Emits the next message in a workflow to the messaging system.
     *
     * This method serializes the given message into a JSON string and sends it using the emitter.
     * If the emission fails, the message is logged, metrics are updated to reflect the failure,
     * and the message is stored in the retry table for reprocessing.
     * The method ensures that no unhandled exceptions are propagated.
     */
    private suspend fun InstanceMessage.emit() = try {
        logger.debug { "Emitting next message: $this" }
        emitter.send(this)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to emit next message. Message will be stored in the $RETRY_TABLE table instead to be re-emit later" }
        metrics.processingFailed(FailureReasons.MESSAGE_EMISSION_ERROR, workflowName, workflowVersion)
        saveForRetry(e)
        throw ProcessingException(e)
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) = try {
        val retryOutboxModel = RetryOutboxModel(
            instance = null,
            message = payload,
            outboxLastError = cause.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(),
            outBoxStatus = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryOutboxModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(UNKNOWN, UNKNOWN)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: ${this.payload}" }
        nack(e, UNKNOWN, UNKNOWN)
    }

    private suspend fun Processor.runFailed(cause: Exception) {
        (instance as InstanceMessage)
            .updateWith(state, position!!)
            .saveAsFailed(cause)
    }

    private suspend fun InstanceMessage.saveAsFailed(cause: Exception?) = try {
        val retryOutboxModel = RetryOutboxModel(
            instance = this,
            message = null,
            outboxLastError = cause?.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(),
            outBoxStatus = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryOutboxModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        message.ack(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: $this" }
        message.nack(e, workflowName, workflowVersion)
    }

    private suspend fun InstanceMessage.saveForRetry(cause: Exception) = try {
        // save the next message for re-emission
        val retryOutboxModel = RetryOutboxModel(
            instance = this,
            outboxLastError = cause.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(), // <- TODO check first date
            outBoxStatus = OutBoxStatus.PENDING,
            message = null,
        )
        retryRepository.insert(retryOutboxModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        message.ack(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert next message for retry, neg-acknowledging the initial message: ${message.payload}" }
        message.nack(e, workflowName, workflowVersion)
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

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    @TestOnly
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

    internal class ProcessingException(cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val UNKNOWN = "unknown"
    }
}
