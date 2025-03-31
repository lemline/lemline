package com.lemline.runtime.messaging

import com.lemline.runtime.logger
import com.lemline.runtime.models.RetryMessage
import com.lemline.runtime.models.WaitMessage
import com.lemline.runtime.repositories.RetryRepository
import com.lemline.runtime.repositories.WaitRepository
import com.lemline.runtime.sw.tasks.activities.WaitInstance
import com.lemline.runtime.sw.workflows.WorkflowInstance
import com.lemline.runtime.sw.workflows.WorkflowParser
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
    private val waitMessageRepository: WaitRepository,
    private val workflowParser: WorkflowParser,
) {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // For testing purposes
    private val processingFutures = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    // For testing purposes
    internal fun waitForProcessing(msg: String): CompletableFuture<Unit> {
        return processingFutures.computeIfAbsent(msg) { CompletableFuture() }
    }

    @Incoming("workflows-in")
    @Outgoing("workflows-out")
    fun consume(msg: String): CompletionStage<String?> = scope.future {
        logger.info("Received workflow execution request: $msg}")

        try {
            processMessage(msg)
        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            handleError(msg, e)
        } finally {
            // Signal that processing is complete
            processingFutures.remove(msg)?.complete(Unit)
        }
    }

    @Transactional
    open suspend fun processMessage(msg: String): String? {
        val workflowMessage = WorkflowMessage.fromJson(msg)
        val instance = WorkflowInstance.from(workflowMessage).apply {
            workflowParser = this@WorkflowConsumer.workflowParser
        }

        instance.run()

        val result = when (instance.status) {
            WorkflowStatus.PENDING -> TODO()
            WorkflowStatus.RUNNING -> instance.running()
            WorkflowStatus.WAITING -> instance.waiting()
            WorkflowStatus.COMPLETED -> null
            WorkflowStatus.FAULTED -> null
            WorkflowStatus.CANCELLED -> null
        }?.toJson()

        return result
    }

    @Transactional
    open fun handleError(msg: String, e: Exception): String? {
        // Store the message for retry
        with(retryRepository) {
            RetryMessage.create(
                message = msg,
                delayedUntil = Instant.now(),
                lastError = e
            ).save()
        }
        // we do not send any message
        return null
    }

    private fun WorkflowInstance.running(): WorkflowMessage {
        return this.toMessage()
    }

    private fun WorkflowInstance.waiting(): WorkflowMessage? {
        val msg = this.toMessage()
        val delay: Duration = (this.currentNodeInstance as WaitInstance).delay
        val delayedUntil = Instant.now().plus(delay.toJavaDuration())

        // Save message to outbox for delayed sending
        with(waitMessageRepository) {
            WaitMessage.create(
                message = msg.toJson(),
                delayedUntil = delayedUntil
            ).save()
        }

        return null
    }
} 