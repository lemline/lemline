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
import com.lemline.core.workflows.WorkflowInstance
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
import com.lemline.runner.models.RETRY_TABLE
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly
import org.reactivestreams.Publisher

internal const val WORKFLOW_IN = "workflows-in"
internal const val WORKFLOW_OUT = "workflows-out"

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@OptIn(ExperimentalTime::class)
@Startup
@ApplicationScoped
internal class ReactiveMessageHandler @Inject constructor(
    @ConfigProperty(name = MESSAGING_CONSUMER_CONCURRENCY) private val maxConcurrency: Int,
    @ConfigProperty(name = CONSUMER_ENABLED) private val enabled: Boolean,
    @Channel(WORKFLOW_IN) private val publisher: Publisher<Message<String>>,
    @Channel(WORKFLOW_OUT) private val emitter: Emitter<String>,
    private val definitionRepository: DefinitionRepository,
    private val retryRepository: RetryRepository,
    private val stepByStepRunner: StepByStepRunner,
    private val metrics: MessageSubscriberMetrics
) {
    val logger = logger()

    // The subscriber is now initialized with the new handleMessage signature
    private val subscriber = ReactiveMessageSubscriber(publisher, ::handleMessage, maxConcurrency, metrics, logger)

    @PostConstruct
    fun init() {
        if (enabled) {
            logger.info { "✅ Consumer enabled" }
            subscriber.subscribe()
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
     * @param message The raw reactive message from the messaging system.
     */
    suspend fun handleMessage(message: Message<String>) {
        // --- Deserialization ---
        val instanceMessage = message.deserialize() ?: return

        try {
            with(instanceMessage) {
                metrics.recordProcessingDuration(workflowName, workflowVersion) {
                    withLoggingContext(
                        LogContext.WORKFLOW_ID to workflowId,
                        LogContext.WORKFLOW_NAME to workflowName,
                        LogContext.WORKFLOW_VERSION to workflowVersion,
                        LogContext.NODE_POSITION to workflowPosition.serialized,
                    ) {
                        // --- Get Workflow Definition ---
                        val workflow = findWorkflowDefinition()
                        // --- Get secrets for this workflow ---
                        val secrets = findSecrets(workflow)
                        // --- Get an instance of this workflow ---
                        val workflowInstance = getWorkflowInstance(secrets)
                        // --- Run this instance ---
                        val nextMessage = run(workflowInstance)
                        // --- Emit next message if any ---
                        nextMessage?.emit()
                        // --- Acknowledgment (Success Path) ---
                        message.ack(workflowName, workflowVersion)
                        // For testing: complete the future
                        processingMessages.remove(message.payload)?.complete(nextMessage?.payload)
                    }
                    metrics.processingCompleted(workflowName, workflowVersion)
                }
            }
        } catch (e: ProcessingException) {
            // For testing: complete the future
            processingMessages.remove(message.payload)?.completeExceptionally(e.cause)
        }
    }

    /**
     * Deserializes the message payload. Returns the Message object on success, or null on failure.
     * Handles its own metrics, logging, and persistence of failed messages.
     */
    private suspend fun Message<String>.deserialize(): InstanceMessage? = try {
        val body = metrics.recordDeserializationDuration {
            InstanceMessage.fromMessage(this)
        }
        metrics.deserializationCompleted(body.workflowName, body.workflowVersion)
        body
    } catch (e: Exception) {
        logger.error(e) { "Failed to deserialize message: $payload" }
        metrics.deserializationFailed(getFailureReason(e))
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
        Workflows.getOrNull(workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowName, workflowVersion)
                ?.definition
                ?.let { Workflows.parseAndPut(it) }
    } catch (e: Exception) {
        logger.error(e) { "Error during workflow definition retrieval" }
        metrics.processingFailed(getFailureReason(e), workflowName, workflowVersion)
        saveForRetry(e)
        throw ProcessingException(e)
    } ?: run {
        // If `workflow` is null
        metrics.processingFailed(DEFINITION_NOT_FOUND, workflowName, workflowVersion)
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
        metrics.processingFailed(SECRETS_RETRIEVAL_FAILED, workflowName, workflowVersion)
        saveAsFailed(e)
        throw ProcessingException(e)
    }

    /**
     * Constructs a workflow instance based on the provided message and secrets.
     *
     * If an error occurs while constructing the instance, the error is logged,
     * metrics are updated to reflect the failure, and the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.getWorkflowInstance(secrets: Map<String, JsonElement>): WorkflowInstance = try {
        WorkflowInstance(
            id = workflowId,
            name = workflowName,
            version = workflowVersion,
            state = workflowState.parsed,
            position = workflowPosition.parsed,
            secrets = secrets,
        )
    } catch (e: Exception) {
        logger.error(e) { "Failed to convert the message to a workflow instance. Storing it in the $RETRY_TABLE table for manual inspection." }
        metrics.processingFailed(getFailureReason(e), workflowName, workflowVersion)
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
    private suspend fun InstanceMessage.run(instance: WorkflowInstance): InstanceMessage? {
        return try {
            with(stepByStepRunner) { run(instance) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to run instance. Storing current state in the $RETRY_TABLE table for manual inspection." }
            metrics.processingFailed(getFailureReason(e), workflowName, workflowVersion)
            runFailed(e, instance)
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
        emitter.send(payload).await()
        logger.debug { "Emitted next message: ${payload}" }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to emit next message. Message will be stored in the retry table instead to be re-emit later" }
        metrics.processingFailed(MESSAGE_EMISSION_ERROR, workflowName, workflowVersion)
        saveForRetry(e)
        throw ProcessingException(e)
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

    private suspend fun Message<String>.deserializationFailed(cause: Exception) = try {
        val retryModel = RetryModel(
            instance = null,
            message = payload,
            outboxLastError = cause.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(),
            outBoxStatus = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        ack(UNKNOWN, UNKNOWN)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: ${this.payload}" }
        nack(e, UNKNOWN, UNKNOWN)
    }

    private suspend fun InstanceMessage.runFailed(cause: Exception?, workflowInstance: WorkflowInstance) {
        val message = try {
            // create a new message with the current state (add the original message for (neg)acknowledgment)
            InstanceMessage.fromObjects(
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                workflowPosition = workflowInstance.position!!,
                workflowState = workflowInstance.state,
                scheduleId = scheduleId,
                parentId = parentId,
            ).also { it.message = message }
        } catch (e: Exception) {
            logger.error(e) { "Failed to convert the current instance to a message. We save the original message for manual inspection: $payload" }
            // if toLemlineMessage fails, we save the entire message for manual inspection
            saveAsFailed(cause)
            return
        }
        message.saveAsFailed(cause)
    }

    private suspend fun InstanceMessage.saveAsFailed(cause: Exception?) = try {
        val retryModel = RetryModel(
            instance = this,
            message = null,
            outboxLastError = cause?.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(),
            outBoxStatus = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryModel)
        // as the responsibility of the message is transferred to the database, we acknowledge the message
        message.ack(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to insert message as failed, the initial message will be neg-acknowledged: $this" }
        message.nack(e, workflowName, workflowVersion)
    }

    private suspend fun InstanceMessage.saveForRetry(cause: Exception) = try {
        // save the next message for re-emission
        val retryModel = RetryModel(
            instance = this,
            outboxLastError = cause.stackTraceToString(),
            outboxScheduledFor = Clock.System.now(), // <- TODO check first date
            outBoxStatus = OutBoxStatus.PENDING,
            message = null,
        )
        retryRepository.insert(retryModel)
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
