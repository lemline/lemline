package com.lemline.swruntime.messaging

import com.lemline.swruntime.workflows.WorkflowInstance
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionStage

@ApplicationScoped
class WorkflowExecutionConsumer(
    private val producer: WorkflowExecutionProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Incoming("workflow-executions")
    fun consume(workflowMessage: WorkflowExecutionMessage): CompletionStage<Any> = scope.future {
        logger.info("Received workflow execution request: $workflowMessage}")

        try {
            val instance = WorkflowInstance(
                name = workflowMessage.name,
                version = workflowMessage.version,
                state = workflowMessage.state.mapKeys { state -> state.key.toPosition() }.toMutableMap(),
                position = workflowMessage.position.toPosition()
            )

            instance.run()

            when (!instance.isCompleted()) {
                false -> producer
                    .setData(
                        instance.name,
                        instance.version,
                        instance.getState().mapKeys { it.key.jsonPointer },
                        instance.position
                    ).send()

                true -> logger.info("Workflow execution completed successfully")
            }

        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            throw e
        }
    }
} 