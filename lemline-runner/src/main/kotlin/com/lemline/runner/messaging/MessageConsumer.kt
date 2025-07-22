// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.LogContext
import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.trace
import com.lemline.common.withLoggingContext
import com.lemline.core.errors.WorkflowException
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.Workflows
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.config.CONSUMER_ENABLED
import com.lemline.runner.config.MESSAGING_PARALLELISM
import com.lemline.runner.metrics.MessageSubscriberMetrics
import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.secrets.Secrets
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage
import org.reactivestreams.Publisher

internal const val WORKFLOW_IN = "workflows-in"
internal const val WORKFLOW_OUT = "workflows-out"

/**
 * WorkflowConsumer is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel.
 */
@Startup
@ApplicationScoped
internal class MessageConsumer @Inject constructor(
    @ConfigProperty(name = MESSAGING_PARALLELISM) private val maxParallelism: Int,
    @ConfigProperty(name = CONSUMER_ENABLED) private val enabled: Boolean,
    @Channel(WORKFLOW_IN) private val publisher: Publisher<ReactiveMessage<String>>,
    @Channel(WORKFLOW_OUT) private val emitter: Emitter<String>,
    private val definitionRepository: DefinitionRepository,
    private val retryRepository: RetryRepository,
    private val stepByStepRunner: StepByStepRunner,
    private val metrics: MessageSubscriberMetrics
) {
    val logger = logger()

    private val subscriber = MessageSubscriber(publisher, ::handleMessage, maxParallelism, metrics, logger)

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
     * Handles the processing of a message payload. This includes deserialization of the message,
     * processing it according to its workflow, and emitting the next step, if any. Failures during
     * deserialization, processing, or emission are logged and recorded for retries or debugging.
     *
     * The only exceptions thrown here are when a failed message cannot be saved in the database.
     *
     * @param payload The raw message payload in JSON format, typically received from a messaging system.
     */
    suspend fun handleMessage(payload: String) {
        // Generate a unique request ID for this message processing
        val requestId = UUID.randomUUID().toString()

        // Use logging context for all logs in this message processing
        withLoggingContext(
            LogContext.REQUEST_ID to requestId,
            LogContext.CORRELATION_ID to requestId, // Use the same ID for correlation until we extract a better one
        ) {
            logger.debug { "Received message for processing" }

            val message = try {
                logger.trace { "Message content: $payload" }
                Message.fromJsonString(payload)
            } catch (e: Exception) {
                logger.error(e) { "Failed to deserialize message" }
                metrics.failed("deserialization", "unknown", "unknown")
                // we store the message as failed for further inspection
                saveMsgAsFailed(payload, e)
                // as the message is saved as failed, we do not do more
                return
            }

            val workflowName = message.name
            val workflowVersion = message.version

            withLoggingContext(
                LogContext.WORKFLOW_ID to message.states[NodePosition.root]?.workflowId,
                LogContext.WORKFLOW_NAME to workflowName,
                LogContext.WORKFLOW_VERSION to workflowVersion,
                LogContext.NODE_POSITION to message.position.toString(),
            ) {
                val nextMessage: Message?
                try {
                    // --- Step 1: Process the message and get the next step ---
                    nextMessage = metrics.recordTimed(workflowName, workflowVersion) {
                        process(message)
                    }
                    metrics.processed(workflowName, workflowVersion)

                } catch (e: Exception) {
                    // --- Handle Processing Failures ---
                    logger.error(e) { "Failed to process message" }
                    metrics.failed(getFailureReason(e), workflowName, workflowVersion)
                    saveMsgAsFailed(payload, e)
                    // as the message is saved as failed, we do not do more
                    return
                }

                // --- Step 2: Emit the next message (if any) ---
                nextMessage?.toJsonString()?.let { result ->
                    try {
                        logger.debug { "Emitting next message for workflow '$workflowName'" }
                        emitter.send(result)
                    } catch (e: Exception) {
                        // --- Handle Emission Failures ---
                        // This is a critical error. The workflow is now stalled.
                        logger.error(e) { "Failed to emit next message. The workflow may be stalled." }
                        metrics.failed("messaging_emission_error", workflowName, workflowVersion)
                        saveMsgForRetry(payload, e)
                        // as the message is saved for retry, we do not do more
                        return
                    }
                }
                // For testing: complete the future regardless of emission success
                processingMessages.remove(payload)?.complete(nextMessage?.toJsonString())
            }
        }
    }


    /**
     * Determines a low-cardinality failure reason from an exception for use in metrics.
     * This is crucial for creating actionable alerts and dashboards without overwhelming
     * the metrics backend.
     */
    private fun getFailureReason(e: Throwable): String = when (e) {
        // Domain-specific errors from the workflow engine
        is WorkflowException -> "workflow_${e.error.type.lowercase()}" // e.g., "workflow_runtime"

        // --- I/O and Network Errors ---
        is IOException -> "io_error" // General network/file issues

        // --- Application State Errors ---
        is IllegalStateException -> "invalid_state" // Often indicates a programming error

        // --- Fallback for any other uncategorized exception ---
        else -> "processing_error"
    }

    private suspend fun process(message: Message): Message? {
        val name = message.name
        val version = message.version
        // Get workflow definition from the cache or load it from the database
        val workflow = Workflows.getOrNull(name, version) ?: run {
            // Load workflow definition from the database
            val workflowDefinition = definitionRepository.findByNameAndVersion(name, version)
                ?: error("Workflow $name:$version not found")
            // validate the workflow definition and put it in cache
            Workflows.parseAndPut(workflowDefinition.definition)
        }

        return stepByStepRunner.run(message, Secrets.getForWorkflow(workflow))
    }

    private fun saveMsgAsFailed(msg: String, e: Exception) {
        with(stepByStepRunner) {
            saveMsgAsFailed(msg, e)
            // for testing, set the CompletableFuture to a failed state
            processingMessages.remove(msg)?.completeExceptionally(e)
        }
    }


    /**
     * Saves a message with a failed status and logs the associated error.
     * This method is used to record messages that could not be processed successfully
     * by persisting the failure details for further inspection or retries.
     *
     * @param msg The message content that failed to process.
     * @param e The exception that caused the failure, or null if no exception occurred.
     */
    private fun saveMsgAsFailed(msg: String, e: Exception?) {
        logger.error(e) { "Failed to process message: $msg" }

        val retryModel = RetryModel(
            message = msg,
            lastError = e?.stackTraceToString(),
            delayedUntil = Instant.now(),
            status = OutBoxStatus.FAILED,
        )
        retryRepository.insert(retryModel)
    }

    /**
     * Saves a message for re-emission by persisting it as a `RetryModel` in the retry repository.
     * This is used to handle messages that could not be emitted successfully, allowing
     * them to be re-emitted later.
     *
     * @param msg The message content that needs to be retried.
     * @param e The exception that caused the processing failure, or null if no exception occurred.
     */
    private fun saveMsgForRetry(msg: String, e: Exception) {
        val retryModel = RetryModel(
            message = msg,
            lastError = e.stackTraceToString(),
            delayedUntil = Instant.now(),
            status = OutBoxStatus.PENDING,
        )
        retryRepository.insert(retryModel)
    }

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

}
