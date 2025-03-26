package com.lemline.swruntime.messaging

import com.lemline.swruntime.logger
import com.lemline.swruntime.repositories.DelayedMessageRepository
import com.lemline.swruntime.sw.tasks.activities.WaitInstance
import com.lemline.swruntime.sw.workflows.WorkflowInstance
import io.serverlessworkflow.impl.WorkflowStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
class WorkflowConsumer {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Inject
    private lateinit var delayedMessageRepository: DelayedMessageRepository

    @Incoming("workflow-executions-in")
    @Outgoing("workflow-executions-out")
    fun consume(msg: WorkflowMessage): CompletionStage<WorkflowMessage?> = scope.future {
        logger.info("Received workflow execution request: $msg}")

        try {
            val instance = WorkflowInstance.from(msg)

            instance.run()

            when (instance.status) {
                WorkflowStatus.PENDING -> TODO()
                WorkflowStatus.RUNNING -> instance.running()
                WorkflowStatus.WAITING -> null.also { instance.waiting() }
                WorkflowStatus.COMPLETED -> null
                WorkflowStatus.FAULTED -> TODO()
                WorkflowStatus.CANCELLED -> TODO()
            }

        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            throw e
        }
    }

    private fun WorkflowInstance.running(): WorkflowMessage {
        return this.toMessage()
    }

    private fun WorkflowInstance.waiting() {
        val msg = this.toMessage()
        val delay: Duration = (this.current as WaitInstance).delay
        val delayedUntil = Instant.now().plus(delay.toJavaDuration())

        // Serialize the message to JSON string
        val messageJson = msg.toJson()

        // Save message to outbox for delayed sending
        delayedMessageRepository.saveMessage(
            message = messageJson,
            delayedUntil = delayedUntil
        )
    }
} 