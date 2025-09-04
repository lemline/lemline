// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.core.definitions.Definitions
import com.lemline.core.processor.Processor
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.ingestion.FailureIngestionMessage
import com.lemline.runner.ingestion.IngestionMessageEmitter
import com.lemline.runner.ingestion.RetryIngestionMessage
import com.lemline.runner.messaging.DelegatedException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.IDV7
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.RETRY_TABLE
import com.lemline.runner.secrets.Secrets
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
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
    private val instanceEmitter: InstanceMessageEmitter,
    private val ingestionEmitter: IngestionMessageEmitter,
    private val definitionRepository: DefinitionRepository,
    private val stepByStepRunner: StepByStepRunner,
    override val metrics: InstanceMessageSubscriberMetrics,
) : MessageHandler<InstanceMessage> {
    override val logger = logger()

    @TestOnly
    override var onComplete = { _: Message<String>, _: String? -> }

    @TestOnly
    override var onFailure = { _: Message<String>, _: Throwable? -> }

    /**
     * Handles the entire lifecycle of an incoming reactive message. This includes deserialization,
     * processing, emission of the next step, and finally acknowledgment (ack/nack).
     *
     * This function is designed to be resilient and will not throw exceptions, instead handling
     * failures by logging, recording metrics, and saving messages for retry or inspection.
     *
     */
    override suspend fun InstanceMessage.handle(): InstanceMessage? {
        // --- Get Workflow Definition ---
        val workflow = findWorkflowDefinition()
        // --- Get secrets for this workflow ---
        val secrets = findSecrets(workflow)
        // --- Get an instance of this workflow ---
        val workflowInstance = getWorkflowProcessor(secrets)
        // --- Run this instance ---
        return run(workflowInstance)
    }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */
    @Throws(DelegatedException::class)
    override suspend fun Message<String>.deserialize(): InstanceMessage = try {
        metrics.recordDeserializationDuration {
            InstanceMessage.fromMessage(this)
        }.also {
            metrics.deserializationCompleted(it.workflowName, it.workflowVersion)
        }
    } catch (e: Exception) {
        throw DelegatedException {
            logger.error(e) { "Failed to deserialize message: ${toLogString()}" }
            metrics.deserializationFailed(e)
            // Deserialization failure is fatal for this message. Save and ACK.
            deserializationFailed(e)
        }
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     * If the workflow is still not found,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    @Throws(DelegatedException::class)
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow = try {
        // Try to get from cache first, then from repository if not found
        Definitions.getOrNull(workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowName, workflowVersion)
                ?.definition
                ?.let { Definitions.parseAndPut(it) }
    } catch (e: Exception) {
        throw DelegatedException {
            logger.error(e) { "Error during workflow definition retrieval" }
            metrics.processingFailed(e, workflowName, workflowVersion)
            saveForRetry(e)
        }
    } ?: throw DelegatedException {
        metrics.processingFailed(FailureReasons.DEFINITION_NOT_FOUND, workflowName, workflowVersion)
        val cause = IllegalStateException("Workflow ${workflowName}:${workflowVersion} not found")
        saveAsFailed(cause)
    }

    /**
     * Retrieves the necessary secrets for a workflow and handles any errors that occur during the process.
     *
     * If an error occurs while getting the secrets, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    @Throws(DelegatedException::class)
    private suspend fun InstanceMessage.findSecrets(
        workflow: Workflow,
    ): Map<String, JsonElement> = try {
        Secrets.getForWorkflow(workflow)
    } catch (e: Exception) {
        throw DelegatedException {
            logger.error(e) { "Unable to retrieve needed secret. Storing the message in the $RETRY_TABLE table for manual inspection." }
            metrics.processingFailed(FailureReasons.SECRETS_RETRIEVAL_FAILED, workflowName, workflowVersion)
            saveAsFailed(e)
        }
    }

    /**
     * Constructs a workflow instance based on the provided message and secrets.
     *
     * If an error occurs while constructing the instance, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    @Throws(DelegatedException::class)
    private suspend fun InstanceMessage.getWorkflowProcessor(
        secrets: Map<String, JsonElement>
    ): Processor = try {
        Processor(workflowState = workflowState, secrets = secrets)
    } catch (e: Exception) {
        throw DelegatedException {
            logger.error(e) { "Failed to convert the message to a workflow processor. Storing it in the $RETRY_TABLE table for manual inspection." }
            metrics.processingFailed(e, workflowName, workflowVersion)
            saveAsFailed(e)
        }
    }

    /**
     * Executes a workflow instance constructed from the provided message, state, and secrets.
     *
     * This method attempts to run the workflow instance step by step using the `stepByStepRunner`.
     * If execution fails, it logs the failure, updates processing metrics, and stores the message
     * in the retry table for manual inspection.
     */
    @Throws(DelegatedException::class)
    private suspend fun run(processor: Processor): InstanceMessage? {
        return try {
            with(stepByStepRunner) { run(processor) }
        } catch (e: Exception) {
            throw DelegatedException {
                logger.error(e) { "Failed to run instance. Storing current state in the $RETRY_TABLE table for manual inspection." }
                metrics.processingFailed(
                    e,
                    processor.workflowState.workflowName,
                    processor.workflowState.workflowVersion
                )
                processor.runFailed(e)
            }
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
    override suspend fun InstanceMessage.emit() = try {
        logger.debug { "Emitting next message: $this" }
        instanceEmitter.send(this)
    } catch (e: Exception) {
        throw DelegatedException {
            logger.warn(e) { "Failed to emit next message. Message will be stored in the $RETRY_TABLE table instead to be re-emit later" }
            metrics.processingFailed(FailureReasons.MESSAGE_EMISSION_ERROR, workflowName, workflowVersion)
            saveForRetry(e)
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureIngestionMessage.from(
            id = IDV7.from(this),
            payload = payload,
            error = cause,
            reason = FailureReasons.DESERIALISATION_ERROR
        )
        ingestionEmitter.send(failure)
    }

    private suspend fun Processor.runFailed(cause: Exception) {
        (workflowState as InstanceMessage)
            .updateWith(position!!, state)
            .saveAsFailed(cause)
    }

    private suspend fun InstanceMessage.saveAsFailed(cause: Exception) {
        val failure = FailureIngestionMessage.from(
            id = IDV7.from(this.message),
            instance = this,
            error = cause,
        )
        ingestionEmitter.send(failure)
    }

    private suspend fun InstanceMessage.saveForRetry(cause: Exception) {
        val retry = RetryIngestionMessage.from(
            id = IDV7.from(this.message),
            instance = this,
            outboxScheduledFor = Clock.System.now(), // <- TODO check first date
            error = cause
        )
        ingestionEmitter.send(retry)
    }
}
