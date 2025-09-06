// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.core.definitions.Definitions
import com.lemline.core.logger.logger
import com.lemline.core.processor.Processor
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.failures.FailureReasons.DEFINITION_NOT_FOUND
import com.lemline.runner.failures.FailureReasons.DESERIALISATION_ERROR
import com.lemline.runner.failures.FailureReasons.MESSAGE_EMISSION_ERROR
import com.lemline.runner.failures.FailureReasons.SECRETS_RETRIEVAL_FAILED
import com.lemline.runner.failures.FailureReasons.SERIALISATION_ERROR
import com.lemline.runner.failures.FailureReasons.WORKFLOW_INITIALIZATION_ERROR
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.ingestion.FailureIngestionMessage
import com.lemline.runner.ingestion.IngestionMessageEmitter
import com.lemline.runner.ingestion.RetryIngestionMessage
import com.lemline.runner.messaging.CompensationException
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
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    /**
     * Deserializes the message payload. Returns the InstanceMessage
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALISATION_ERROR) { deserializationFailed(e) }
    }

    /**
     * Handles the lifecycle of an incoming instance message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun InstanceMessage.handle(): InstanceMessage? {
        // --- Get Workflow Definition ---
        val workflow = findWorkflowDefinition()
        // --- Get secrets for this workflow ---
        val secrets = findSecrets(workflow)
        // --- Get processor for this workflow ---
        val processor = getProcessor(secrets)
        // --- Run it ---
        return run(processor)
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     *
     * If the workflow is still not found, the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow = try {
        // Try to get from cache first, then from repository if not found
        Definitions.getOrNull(workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowName, workflowVersion)
                ?.definition
                ?.let { Definitions.parseAndPut(it) }
    } catch (e: Exception) {
        logger.error(e) { "Error during workflow definition retrieval." }
        emitToRetry(e, getFailureReason(e))
    } ?: run {
        val errorMsg = "Workflow ${workflowName}:${workflowVersion} not found."
        logger.error { "$errorMsg Storing the message in the $RETRY_TABLE table for manual inspection." }
        emitAsFailed(IllegalStateException(errorMsg), DEFINITION_NOT_FOUND)
    }

    /**
     * Retrieves the necessary secrets for a workflow.
     *
     * If an error occurs while getting the secrets, the error is logged,
     * and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.findSecrets(workflow: Workflow): Map<String, JsonElement> = try {
        Secrets.getForWorkflow(workflow)
    } catch (e: Exception) {
        logger.error(e) { "Unable to retrieve needed secret. Storing the message in the $RETRY_TABLE table for manual inspection." }
        emitAsFailed(e, SECRETS_RETRIEVAL_FAILED)
    }

    /**
     * Constructs a processor instance.
     *
     * If an error occurs while constructing the instance, the error is logged,
     * and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.getProcessor(
        secrets: Map<String, JsonElement>
    ): Processor = try {
        Processor(workflowState = workflowState, secrets = secrets)
    } catch (e: Exception) {
        logger.error(e) { "Failed to init workflow processor. Storing it in the $RETRY_TABLE table for manual inspection." }
        emitAsFailed(e, WORKFLOW_INITIALIZATION_ERROR)
    }

    /**
     * Executes a workflow instance constructed from the provided message, state, and secrets.
     *
     * This method attempts to run the workflow instance step by step using the `stepByStepRunner`.
     * If execution fails, it logs the failure,  and stores the message
     * in the retry table for manual inspection.
     */
    private suspend fun InstanceMessage.run(processor: Processor): InstanceMessage? {
        return try {
            with(stepByStepRunner) { run(processor) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to run instance. Storing current state in the $RETRY_TABLE table for manual inspection." }
            updateFrom(processor).emitAsFailed(e, getFailureReason(e))
        }
    }

    /**
     * Emits the next message in a workflow to the messaging system.
     *
     * This method serializes the given message into a JSON string and sends it using the emitter.
     * If the emission fails, the message is logged,
     * and the message is stored in the retry table for reprocessing.
     * The method ensures that no unhandled exceptions are propagated.
     */
    override suspend fun InstanceMessage.emit() {
        // Serialize the message
        val payload = try {
            this.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize new message. Message will be stored in the $RETRY_TABLE table as failed" }
            emitAsFailed(e, SERIALISATION_ERROR)
        }
        // Emit the message
        try {
            instanceEmitter.send(payload)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit next message. Message will be stored in the $RETRY_TABLE table instead to be re-emit later" }
            emitToRetry(e, MESSAGE_EMISSION_ERROR)
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureIngestionMessage.from(
            id = IDV7.new(),
            payload = payload,
            error = cause,
            reason = DESERIALISATION_ERROR
        )
        ingestionEmitter.send(failure)
    }

    private suspend fun InstanceMessage.emitToRetry(cause: Exception, reason: String): Nothing {
        throw CompensationException(reason) { saveForRetry(cause, reason) }
    }

    private suspend fun InstanceMessage.emitAsFailed(cause: Exception, reason: String): Nothing {
        throw CompensationException(reason) { saveAsFailed(cause, reason) }
    }

    private suspend fun InstanceMessage.saveAsFailed(error: Exception, reason: String) {
        val failure = FailureIngestionMessage.from(
            id = IDV7.new(),
            instance = this,
            error = error,
            reason = reason,
        )
        ingestionEmitter.send(failure)
    }

    private suspend fun InstanceMessage.saveForRetry(cause: Exception, reason: String) {
        val retry = RetryIngestionMessage.from(
            id = IDV7.new(),
            instance = this,
            outboxScheduledFor = Clock.System.now(), // <- TODO check first date
            error = cause,
            reason = reason,
        )
        ingestionEmitter.send(retry)
    }
}
