package com.lemline.swruntime.messaging

import com.lemline.swruntime.workflows.WorkflowInstance
import com.lemline.swruntime.workflows.WorkflowService
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.slf4j.LoggerFactory
import java.time.Instant

@ApplicationScoped
class WorkflowExecutionConsumer(
    private val workflowService: WorkflowService,
    private val workflowExecutionProducer: WorkflowExecutionProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Incoming("workflow-executions")
    fun consume(request: WorkflowExecutionRequest) {
        val workflowRequest = request.workflow
        val instanceRequest = request.instance
        val currentTaskRequest = request.currentTask
        logger.info("Received workflow execution request: ${workflowRequest.name}:${workflowRequest.version} (${instanceRequest.id})")

        try {
            // Get and validate workflow definition
            val workflow = workflowService.getWorkflow(workflowRequest.name, workflowRequest.version)

            val instance = WorkflowInstance(
                workflowName = workflowRequest.name,
                workflowVersion = workflowRequest.version,
                instanceId = instanceRequest.id,
                instanceRawInput = instanceRequest.rawInput,
                instanceContext = instanceRequest.context,
                instanceStartedAt = DateTimeDescriptor.from(Instant.parse(instanceRequest.startedAt))
            )

            val nextTaskRequest = when (currentTaskRequest) {
                null -> instance.start()
                else -> instance.runTask(currentTaskRequest)
            }

        } catch (e: Exception) {
            logger.error("Error processing workflow execution request", e)
            throw e
        }
    }
} 