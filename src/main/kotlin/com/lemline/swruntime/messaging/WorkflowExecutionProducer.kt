package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.tasks.TaskPosition
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Outgoing
import org.slf4j.LoggerFactory

@ApplicationScoped
class WorkflowExecutionProducer {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var workflowRequest: WorkflowRequest
    private lateinit var instanceRequest: InstanceRequest
    private var currentTask: TaskRequest? = null

    fun setData(
        workflowName: String,
        workflowVersion: String,
        instanceId: String,
        instanceRawInput: JsonNode,
        instanceContext: ObjectNode,
        instanceStartedAt: DateTimeDescriptor,
        taskRawInput: JsonNode,
        taskPosition: TaskPosition,
    ) {
        this.workflowRequest = WorkflowRequest(workflowName, workflowVersion)
        this.instanceRequest =
            InstanceRequest(instanceId, instanceRawInput, instanceContext, instanceStartedAt.iso8601())
        this.currentTask = TaskRequest(taskRawInput, taskPosition.jsonPointer())
    }

    @Outgoing("workflow-executions")
    fun sendNextTask(): WorkflowExecutionRequest {
        logger.info(
            "Sending next task for workflow {}:{} ({}) at position {}",
            workflowRequest.name, workflowRequest.version, instanceRequest.id, currentTask?.position
        )

        return WorkflowExecutionRequest(workflowRequest, instanceRequest, currentTask)
    }
}