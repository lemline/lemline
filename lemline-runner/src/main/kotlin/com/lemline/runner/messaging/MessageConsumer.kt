// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.LogContext
import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.trace
import com.lemline.common.withLoggingContext
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.Workflows
import com.lemline.runner.StepByStepRunner
import com.lemline.runner.config.CONSUMER_ENABLED
import com.lemline.runner.config.MESSAGING_PARALLELISM
import com.lemline.runner.metrics.MessageSubscriberMetrics
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.secrets.Secrets
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
    private val stepByStepRunner: StepByStepRunner,
    metrics: MessageSubscriberMetrics
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
                // save to retry table with a status of FAILED
                saveMsgAsFailed(payload, e)
                // Send message to dead letter queue
                // NOTE - MUST have mp.messaging.incoming.workflows-in.failure-strategy=dead-letter-queue
                // If not, Quarkus will stop consuming messages
                throw e
            }

            // Extract workflow ID from the root state if available
            val workflowId = message.states[NodePosition.root]?.workflowId

            // Add workflow context information once we have it
            withLoggingContext(
                LogContext.WORKFLOW_ID to workflowId,
                LogContext.WORKFLOW_NAME to message.name,
                LogContext.WORKFLOW_VERSION to message.version,
                LogContext.NODE_POSITION to message.position.toString(),
            ) {
                try {
                    logger.debug { "Processing workflow message: ${message.toJsonString()}" }
                    val next = process(message)
                    next?.toJsonString().also { result ->
                        if (result != null) {
                            logger.debug { "Workflow processing completed with next message:\n${next?.toJsonString()}" }
                            // Send the next message to the outgoing channel
                            emitter.send(result)
                        } else {
                            logger.debug { "Workflow processing completed without next message" }
                        }
                        processingMessages.remove(payload)?.complete(result)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process workflow message" }
                    saveMsgAsFailed(payload, e)
                    // Send the message to dead letter queue
                    // NOTE - we MUST set mp.messaging.incoming.workflows-in.failure-strategy=dead-letter-queue
                    // If not, Quarkus will stop consuming messages
                    throw e
                }
            }
        }
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
            msg.saveMsgAsFailed(e)
            // for testing, set the CompletableFuture to a failed state
            processingMessages.remove(msg)?.completeExceptionally(e)
        }
    }

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

}
