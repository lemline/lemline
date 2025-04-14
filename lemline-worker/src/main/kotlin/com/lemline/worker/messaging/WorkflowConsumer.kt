package com.lemline.worker.messaging

import com.lemline.common.logger
import com.lemline.core.nodes.activities.WaitInstance
import com.lemline.core.nodes.flows.TryInstance
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.core.workflows.Workflows
import com.lemline.worker.models.RetryModel
import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.repositories.RetryRepository
import com.lemline.worker.repositories.WaitRepository
import com.lemline.worker.repositories.WorkflowRepository
import com.lemline.worker.secrets.Secrets
import io.serverlessworkflow.impl.WorkflowStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@ApplicationScoped
open class WorkflowConsumer(
    private val workflowRepository: WorkflowRepository,
    private val retryRepository: RetryRepository,
    private val waitRepository: WaitRepository,
) {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Incoming("workflows-in")
    @Outgoing("workflows-out")
    fun consume(msg: String): CompletionStage<String?> = scope.future {
        val workflowMessage = try {
            logger.debug("Received request: $msg")
            WorkflowMessage.fromJsonString(msg)
        } catch (e: Exception) {
            logger.error("Unable to deserialize msg $msg", e)
            // save to retry table with a status of FAILED
            msg.saveMsgAsFailed(e)
            // Send message to dead letter queue
            // NOTE - we MUST set mp.messaging.incoming.workflows-in.failure-strategy=dead-letter-queue
            // If not, Quarkus will stop consuming messages
            throw e
        }

        try {
            process(workflowMessage).also {
                logger.debug("Processed request: $it}")
                processingMessages.remove(msg)?.complete(it)
            }
        } catch (e: Exception) {
            logger.error("Unable to process $workflowMessage", e)
            msg.saveMsgAsFailed(e)
            // Send message to dead letter queue
            // NOTE - we MUST set mp.messaging.incoming.workflows-in.failure-strategy=dead-letter-queue
            // If not, Quarkus will stop consuming messages
            throw e
        }
    }

    @Transactional
    open suspend fun process(workflowMessage: WorkflowMessage): String? {
        val name = workflowMessage.name
        val version = workflowMessage.version
        // Get workflow definition from cache or load it from the database
        val workflow = Workflows.getOrNull(name, version) ?: run {
            // Load workflow definition from database
            val workflowDefinition = workflowRepository.findByNameAndVersion(name, version)
                ?: error("Workflow $name:$version not found")
            // load and validate workflow definition
            Workflows.parseAndPut(workflowDefinition.definition)
        }

        val instance = WorkflowInstance(
            name = workflowMessage.name,
            version = workflowMessage.version,
            initialStates = workflowMessage.states,
            initialPosition = workflowMessage.position,
            secrets = Secrets.get(workflow)
        )

        instance.run()

        val nextMessage = when (instance.status) {
            WorkflowStatus.PENDING -> TODO()
            WorkflowStatus.WAITING -> instance.waiting()
            WorkflowStatus.RUNNING -> when (instance.currentNodeInstance is TryInstance) {
                true -> instance.retry()
                else -> instance.running()
            }

            WorkflowStatus.COMPLETED -> null // Nothing to do
            WorkflowStatus.FAULTED -> instance.faulted()
            WorkflowStatus.CANCELLED -> TODO()
        }?.toJsonString()

        return nextMessage
    }

    private fun String.saveMsgAsFailed(e: Exception?) {
        // Store the message in retry in failed state (for information)
        retryRepository.save(
            RetryModel.create(
                message = this@saveMsgAsFailed,
                delayedUntil = Instant.now(),
                lastError = e,
                status = OutBoxStatus.FAILED
            )
        )
        // for testing, set the CompletableFuture to failed
        processingMessages.remove(this)?.completeExceptionally(e)
    }

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

    private fun WorkflowInstance.running(): WorkflowMessage = WorkflowMessage(
        name = this.name,
        version = this.version,
        states = this.currentNodeStates,
        position = this.currentNodePosition,
    )

    private fun WorkflowInstance.faulted(): WorkflowMessage? {
        // Store the message in retry in failed state (for information)
        toMessage().toJsonString().saveMsgAsFailed(null)
        // Stop the processing of this instance
        return null
    }

    private fun WorkflowInstance.retry(): WorkflowMessage? {
        val msg = this.toMessage()
        val delay = (currentNodeInstance as TryInstance).delay
        val delayedUntil = Instant.now().plus(delay?.toJavaDuration() ?: error("No delay set in for $this"))

        // Save message to the retry table
        retryRepository.save(
            RetryModel.create(
                message = msg.toJsonString(),
                delayedUntil = delayedUntil
            )
        )

        // Stop here instance, the outbox will process it later
        return null
    }

    private fun WorkflowInstance.waiting(): WorkflowMessage? {
        val msg = this.toMessage()
        val delay: Duration = (this.currentNodeInstance as WaitInstance).delay
        val delayedUntil = Instant.now().plus(delay.toJavaDuration())

        // Save message to the wait table
        waitRepository.save(
            WaitModel.create(
                message = msg.toJsonString(),
                delayedUntil = delayedUntil
            )
        )
        // Stop here instance, the outbox will process it later
        return null
    }

    private fun WorkflowInstance.toMessage() = WorkflowMessage(
        name = this.name,
        version = this.version,
        states = this.currentNodeStates,
        position = this.currentNodePosition,
    )
} 