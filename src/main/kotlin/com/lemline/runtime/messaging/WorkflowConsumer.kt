package com.lemline.runtime.messaging

import com.lemline.runtime.logger
import com.lemline.runtime.models.RetryMessage
import com.lemline.runtime.models.WaitMessage
import com.lemline.runtime.repositories.RetryRepository
import com.lemline.runtime.repositories.WaitRepository
import com.lemline.runtime.sw.tasks.activities.WaitInstance
import com.lemline.runtime.sw.workflows.WorkflowInstance
import io.serverlessworkflow.impl.WorkflowStatus
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import java.time.Instant
import java.util.concurrent.CompletionStage
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@ApplicationScoped
internal class WorkflowConsumer(
    private val retryRepository: RetryRepository,
    private val waitMessageRepository: WaitRepository,
) {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Incoming("workflows-in")
    @Outgoing("workflows-out")
    fun consume(msg: WorkflowMessage): CompletionStage<String?> = scope.future {
        logger.info("Received workflow execution request: $msg}")

        try {
            val instance = WorkflowInstance.from(msg)
            instance.run()

            when (instance.status) {
                WorkflowStatus.PENDING -> TODO()
                WorkflowStatus.RUNNING -> instance.running()
                WorkflowStatus.WAITING -> instance.waiting()
                WorkflowStatus.COMPLETED -> null
                WorkflowStatus.FAULTED -> null
                WorkflowStatus.CANCELLED -> null
            }?.toJson()
        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            // Instead of throwing, we'll store the message for retry
            // if an error occurs here also, scope.future will fail and the message sent to DLQ
            with(retryRepository) {
                RetryMessage.create(
                    message = msg.toJson(),
                    delayedUntil = Instant.now(),
                    lastError = e
                ).save()
            }
            null
        }
    }

    private fun WorkflowInstance.running(): WorkflowMessage {
        return this.toMessage()
    }

    private fun WorkflowInstance.waiting(): WorkflowMessage? {
        val msg = this.toMessage()
        val delay: Duration = (this.current as WaitInstance).delay
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