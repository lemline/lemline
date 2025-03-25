package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.swruntime.logger
import com.lemline.swruntime.services.DelayedMessageService
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage

@ApplicationScoped
class WorkflowConsumer {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val objectMapper = ObjectMapper()

    @Inject
    private lateinit var delayedMessageService: DelayedMessageService

    @Incoming("workflow-executions-in")
    @Outgoing("workflow-executions-out")
    fun consume(msg: WorkflowMessage): CompletionStage<WorkflowMessage?> = scope.future {
        logger.info("Received workflow execution request: $msg}")

        try {
            val instance = WorkflowInstance.from(msg)

            instance.run()

            when (instance.status) {
                WorkflowStatus.PENDING -> TODO()
                WorkflowStatus.RUNNING -> instance.toMessage()
                WorkflowStatus.WAITING -> null.also { wait(instance) }
                WorkflowStatus.COMPLETED -> null
                WorkflowStatus.FAULTED -> TODO()
                WorkflowStatus.CANCELLED -> TODO()
            }

        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            throw e
        }
    }

    private fun wait(instance: WorkflowInstance) {
        val msg = instance.toMessage()
        val delay: Duration = Duration.ZERO //ninstance.getCurrentTaskDuration()
        val delayedUntil = Instant.now().plus(delay)

        // Serialize the message to JSON string
        val messageJson = objectMapper.writeValueAsString(msg)

        // Save message to outbox for delayed sending
        delayedMessageService.saveMessage(
            message = messageJson,
            delayedUntil = delayedUntil
        )
    }
} 