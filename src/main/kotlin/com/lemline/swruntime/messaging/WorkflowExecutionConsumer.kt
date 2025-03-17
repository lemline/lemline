package com.lemline.swruntime.messaging

import com.lemline.swruntime.workflows.WorkflowInstance
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.slf4j.LoggerFactory

@ApplicationScoped
class WorkflowExecutionConsumer(
    private val producer: WorkflowExecutionProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Incoming("workflow-executions")
    suspend fun consume(workflowMessage: WorkflowExecutionMessage) {
        logger.info("Received workflow execution request: $workflowMessage}")

        try {
            // Get and validate workflow definition

            val instance = WorkflowInstance(
                name = workflowMessage.name,
                version = workflowMessage.version,
                state = workflowMessage.state.mapKeys { state -> state.key.toPosition() }.toMutableMap(),
                position = workflowMessage.position.toPosition()
            )

            instance.run()

            when (instance.isCompleted()) {
                false -> producer
                    .setData(
                        instance.name,
                        instance.version,
                        instance.getState().mapKeys { state -> state.key.jsonPointer },
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