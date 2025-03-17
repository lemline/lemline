package com.lemline.swruntime.messaging

import com.lemline.swruntime.tasks.JsonPointer
import com.lemline.swruntime.tasks.NodePosition
import com.lemline.swruntime.tasks.NodeState
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Outgoing
import org.slf4j.LoggerFactory

@ApplicationScoped
class WorkflowExecutionProducer {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var message: WorkflowExecutionMessage

    fun setData(
        workflowName: String,
        workflowVersion: String,
        instanceStates: Map<JsonPointer, NodeState>,
        instancePosition: NodePosition,
    ): WorkflowExecutionProducer {
        message = WorkflowExecutionMessage(
            name = workflowName,
            version = workflowVersion,
            state = instanceStates,
            position = instancePosition.jsonPointer
        )

        return this
    }

    @Outgoing("workflow-executions")
    fun send(): WorkflowExecutionMessage {
        logger.info("Sending message: {}", message)

        return message
    }
}