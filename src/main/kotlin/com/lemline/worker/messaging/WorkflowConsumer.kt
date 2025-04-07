package com.lemline.worker.messaging

import com.lemline.common.logger
import com.lemline.sw.nodes.activities.WaitInstance
import com.lemline.sw.nodes.flows.TryInstance
import com.lemline.sw.workflows.WorkflowInstance
import com.lemline.sw.workflows.WorkflowParser
import com.lemline.worker.models.RetryMessage
import com.lemline.worker.models.WaitMessage
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.repositories.RetryRepository
import com.lemline.worker.repositories.WaitRepository
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
    private val retryRepository: RetryRepository,
    private val waitRepository: WaitRepository,
    private val workflowParser: WorkflowParser,
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
            // message will be sent to dead letter queue
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
            // message will be sent to dead letter queue
            throw e
        }
    }

    @Transactional
    open suspend fun process(workflowMessage: WorkflowMessage): String? {
        val instance = WorkflowInstance.from(workflowMessage).apply {
            workflowParser = this@WorkflowConsumer.workflowParser
        }

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

    // For testing purposes
    private val processingMessages = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<String?> =
        processingMessages.computeIfAbsent(msg) { CompletableFuture() }

    private fun WorkflowInstance.running(): WorkflowMessage = this.toMessage()

    private fun String.saveMsgAsFailed(e: Exception?) {
        // Store the message in retry in failed state (for information)
        with(retryRepository) {
            RetryMessage.create(
                message = this@saveMsgAsFailed,
                delayedUntil = Instant.now(),
                lastError = e,
                status = OutBoxStatus.FAILED
            ).save()
        }
        // for testing, set the CompletableFuture to failed
        processingMessages.remove(this)?.completeExceptionally(e)
    }

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
        with(retryRepository) {
            RetryMessage.create(
                message = msg.toJsonString(),
                delayedUntil = delayedUntil
            ).save()
        }
        // Stop here instance, the outbox will process it later
        return null
    }

    private fun WorkflowInstance.waiting(): WorkflowMessage? {
        val msg = this.toMessage()
        val delay: Duration = (this.currentNodeInstance as WaitInstance).delay
        val delayedUntil = Instant.now().plus(delay.toJavaDuration())

        // Save message to the wait table
        with(waitRepository) {
            WaitMessage.create(
                message = msg.toJsonString(),
                delayedUntil = delayedUntil
            ).save()
        }
        // Stop here instance, the outbox will process it later
        return null
    }
} 