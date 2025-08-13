// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.LogContext
import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.common.withLoggingContext
import com.lemline.core.errors.WorkflowException
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.Workflows
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.config.CONSUMER_ENABLED
import com.lemline.runner.config.MESSAGING_CONSUMER_CONCURRENCY
import com.lemline.runner.metrics.MessageSubscriberMetrics
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.DATABASE_ERROR
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.DEFINITION_NOT_FOUND
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.INVALID_STATE
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.IO_ERROR
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.MESSAGE_EMISSION_ERROR
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.PROCESSING_ERROR
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.SECRETS_RETRIEVAL_FAILED
import com.lemline.runner.metrics.MessageSubscriberMetrics.Companion.FailureReasons.WORKFLOW_ERROR_PREFIX
import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.secrets.Secrets
import io.quarkus.runtime.Startup
import io.serverlessworkflow.api.types.Workflow
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.IOException
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage
import org.reactivestreams.Publisher

internal const val WORKFLOW_IN = "workflows-in"
internal const val WORKFLOW_OUT = "workflows-out"

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@Startup
@ApplicationScoped
internal class MessageHandler @Inject constructor(
    @ConfigProperty(name = MESSAGING_CONSUMER_CONCURRENCY) private val maxConcurrency: Int,
    @ConfigProperty(name = CONSUMER_ENABLED) private val enabled: Boolean,
    @Channel(WORKFLOW_IN) private val publisher: Publisher<ReactiveMessage<String>>,
    @Channel(WORKFLOW_OUT) private val emitter: Emitter<String>,
    private val definitionRepository: DefinitionRepository,
    private val retryRepository: RetryRepository,
    private val stepByStepRunner: StepByStepRunner,
    private val metrics: MessageSubscriberMetrics
) {
    val logger = logger()

    // The subscriber is now initialized with the new handleMessage signature
    private val subscriber = MessageSubscriber(publisher, ::handleMessage, maxConcurrency, metrics, logger)

    @PostConstruct
    fun init() {
        if (enabled) {
            subscriber.subscribe()
            logger.info { "✅ Consumer enabled" }
        } else {
            logger.info { "❌ Consumer disabled" }
        }
    }

    @PreDestroy
    fun shutdown() {
        subscriber.onShutdown()
    }

    /**
     * Handles the entire lifecycle of an incoming reactive message. This includes deserialization,
     * processing, emission of the next step, and finally acknowledgment (ack/nack).
     *
     * This function is designed to be resilient and will not throw exceptions, instead handling
     * failures by logging, recording metrics, and saving messages for retry or inspection.
     *
     * @param item The raw reactive message from the messaging system.
     */
    suspend fun handleMessage(item: ReactiveMessage<String>) {
        // --- Step 1: Deserialization ---
        val message = deserializeMessage(item) ?: return

        val workflowName = message.name
        val workflowVersion = message.version

        withLoggingContext(
            LogContext.WORKFLOW_ID to message.states[NodePosition.root]?.workflowId,
            LogContext.WORKFLOW_NAME to workflowName,
            LogContext.WORKFLOW_VERSION to workflowVersion,
            LogContext.NODE_POSITION to message.position.toString(),
        ) {
            metrics.recordProcessingDuration(workflowName, workflowVersion) {
                try {
                    // --- Step 2: Get Workflow Definition ---
                    val workflow = findWorkflowDefinition(item, workflowName, workflowVersion)
                    // --- Step 3: Get secrets for this workflow ---
                    val secrets = findSecrets(item, workflow)
                    // --- Step 4: Run instance ---
                    val nextMessage = runInstance(item, message, secrets)
                    // --- Step 5: Emit next message ---
                    emitMessage(item, nextMessage)
                    // --- Step 6: Acknowledgment (Success Path) ---
                    metrics.processingCompleted(workflowName, workflowVersion)
                    ack(item, workflowName, workflowVersion)
                    // For testing: complete the future
                    processingMessages.remove(item.payload)?.complete(nextMessage?.toJsonString())
                } catch (e: ProcessingException) {
                    // For testing: complete the future
                    processingMessages.remove(item.payload)?.completeExceptionally(e.cause)
                }
            }
        }
    }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */
    private suspend fun deserializeMessage(item: ReactiveMessage<String>): Message? = try {
        val message = metrics.recordDeserializationDuration { Message.fromJsonString(item.payload) }
        metrics.deserializationCompleted(message.name, message.version)
        message
    } catch (e: Exception) {
        logger.error(e) { "Failed to deserialize message: ${item.payload}" }
        metrics.deserializationFailed(getFailureReason(e))
        // Deserialization failure is fatal for this message. Save and NACK.
        saveMsgAsFailed(item, item.payload, e, UNKNOWN, UNKNOWN)
        null
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     * If the workflow is still not found, it handles the error and returns null.
     */
    private suspend fun findWorkflowDefinition(
        item: ReactiveMessage<String>,
        workflowName: String,
        workflowVersion: String
    ): Workflow {
        // Try to get from cache first, then from repository if not found
        val workflow = Workflows.getOrNull(workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowName, workflowVersion)
                ?.definition
                ?.let { Workflows.parseAndPut(it) }

        // If workflow is null, handle the error
        if (workflow == null) {
            metrics.processingFailed(DEFINITION_NOT_FOUND, workflowName, workflowVersion)
            val cause = IllegalStateException("Workflow $workflowName:$workflowVersion not found")
            saveMsgAsFailed(item, item.payload, cause, workflowName, workflowVersion)
            throw ProcessingException(cause = cause)
        }

        return workflow

    }

    /**
     * Retrieves the necessary secrets for a workflow and handles any errors that occur during the process.
     *
     * If an error occurs while obtaining the secrets, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    private suspend fun findSecrets(
        item: ReactiveMessage<String>,
        workflow: Workflow,
    ): Map<String, JsonElement> = try {
        Secrets.getForWorkflow(workflow)
    } catch (e: Exception) {
        logger.error(e) { "Unable to get needed secrets" }
        metrics.processingFailed(SECRETS_RETRIEVAL_FAILED, workflow.document.name, workflow.document.version)
        saveMsgAsFailed(item, item.payload, e, workflow.document.name, workflow.document.version)
        throw ProcessingException(cause = e)
    }

    /**
     * Executes a workflow instance constructed from the provided message, state, and secrets.
     *
     * This method attempts to run the workflow instance step by step using the `stepByStepRunner`.
     * If execution fails, it logs the failure, updates processing metrics, and stores the message
     * in the retry table for manual inspection.
     */
    private suspend fun runInstance(
        item: ReactiveMessage<String>,
        message: Message,
        secrets: Map<String, JsonElement>
    ): Message? {
        val instance = message.toWorkflowInstance(secrets)
        return try {
            stepByStepRunner.run(instance)
        } catch (e: Exception) {
            logger.error(e) { "Failed to run instance. Storing current state in the retry table for manual inspection." }
            metrics.processingFailed(getFailureReason(e), message.name, message.version)
            saveMsgAsFailed(item, instance.toMessage().toJsonString(), e, message.name, message.version)
            throw ProcessingException(cause = e)
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
    private suspend fun emitMessage(
        item: ReactiveMessage<String>,
        nextMessage: Message?
    ) {
        val msg = nextMessage?.toJsonString() ?: return
        return try {
            emitter.send(msg).await()
            logger.debug { "Emitted next message: $msg" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit next message. Message will be stored in the retry table instead to be re-emit later" }
            metrics.processingFailed(MESSAGE_EMISSION_ERROR, nextMessage.name, nextMessage.version)
            saveMsgForRetry(item, msg, e, nextMessage.name, nextMessage.version)
            throw ProcessingException(cause = e)
        }
    }

    /**
     * Determines a low-cardinality failure reason from an exception for use in metrics.
     * This is crucial for creating actionable alerts and dashboards without overwhelming
     * the metrics backend.
     */
    private fun getFailureReason(e: Throwable): String = when (e) {
        // Domain-specific errors from the workflow engine
        is WorkflowException -> WORKFLOW_ERROR_PREFIX + e.error.type.lowercase()

        // --- Database & Persistence Errors ---
        is SQLException -> DATABASE_ERROR

        // --- I/O and Network Errors ---
        is IOException -> IO_ERROR

        // --- Application State Errors ---
        is IllegalStateException -> INVALID_STATE

        // --- Fallback for any other uncategorized exception ---
        else -> PROCESSING_ERROR
    }

    private suspend fun saveMsgAsFailed(
        current: ReactiveMessage<String>,
        next: String,
        cause: Exception?,
        workflowName: String,
        workflowVersion: String
    ) = try {
        val retryModel = RetryModel(
            message = next,
            lastError = cause?.stackTraceToString(),
            delayedUntil = Instant.now(),
            status = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(current, workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: $next" }
        nack(current, e, workflowName, workflowVersion)
    }

    private suspend fun saveMsgForRetry(
        current: ReactiveMessage<String>,
        next: String,
        cause: Exception,
        workflowName: String,
        workflowVersion: String
    ) = try {
        // save the next message for re-emission
        val retryModel = RetryModel(
            message = next,
            lastError = cause.stackTraceToString(),
            delayedUntil = Instant.now(),
            status = OutBoxStatus.PENDING,
        )
        retryRepository.insert(retryModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(current, workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert next message for retry, neg-acknowledging the initial message: $next" }
        nack(current, e, workflowName, workflowVersion)
    }

    /**
     * Acknowledges a reactive message to indicate successful processing.
     * If the acknowledgment fails, logs the error and increments the failure metrics counter.
     *
     * @param item The reactive message being acknowledged.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    private fun ack(item: ReactiveMessage<String>, workflowName: String, workflowVersion: String) {
        try {
            logger.debug { "ACKing message: ${item.payload}" }
            item.ack()
            metrics.ackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.error(e) { "CRITICAL: Failed to ACK message. Duplicate processing may occur: ${item.payload}" }
            metrics.ackFailed(workflowName, workflowVersion)
        }
    }

    /**
     * Handles the negative acknowledgment (NACK) for a reactive message, including logging,
     * metrics increment, and exception handling if the NACK operation fails.
     *
     * @param item The reactive message to be negatively acknowledged.
     * @param reason The exception that triggered the negative acknowledgment.
     * @param workflowName The name of the workflow associated with the message.
     * @param workflowVersion The version of the workflow associated with the message.
     */
    private fun nack(item: ReactiveMessage<String>, reason: Exception, workflowName: String, workflowVersion: String) {
        try {
            logger.debug { "NACKing message: ${item.payload}" }
            item.nack(reason)
            metrics.nackCompleted(workflowName, workflowVersion)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to NACK message. This message should be represented by brokers: ${item.payload}" }
            metrics.nackFailed(workflowName, workflowVersion)
        }
    }

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

    internal class ProcessingException(cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val UNKNOWN = "unknown"
    }
}
