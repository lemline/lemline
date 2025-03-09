package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.ExpressionScope
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.messaging.TaskRequest
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.TaskPosition
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import jakarta.inject.Inject

class WorkflowInstance(
    val workflowName: String,
    val workflowVersion: String,
    val instanceId: String,
    val instanceRawInput: JsonNode,
    val instanceContext: ObjectNode,
    val instanceStartedAt: DateTimeDescriptor
) {
    @Inject
    private lateinit var workflowService: WorkflowService

    private val workflow by lazy { workflowService.getWorkflow(workflowName, workflowVersion) }

    private lateinit var currentTaskPosition: TaskPosition

    private lateinit var expressionScope: ExpressionScope

    fun start(): TaskRequest {
        validateInstanceInputSchema()

        val fromInput = transformInstanceRawInput()

        // look for the do task
        currentTaskPosition = TaskPosition.fromString("/do")

        // run the do task
        val task = workflowService.getTask(workflow, "/do")

        TODO()
    }

    /**
     * Validates the instance raw input against the workflow's input schema if it exists.
     *
     * This method checks if the workflow has an input schema defined. If a schema is present,
     * it validates the `instanceRawInput` against this schema using the `SchemaValidator`.
     */
    fun validateInstanceInputSchema() {
        workflow.input.schema?.let { schema -> SchemaValidator.validate(instanceRawInput, schema) }
    }


    /**
     * Transforms the instance raw input using the workflow's input transformation expression.
     *
     * This method performs the following steps:
     * 1. Creates a `WorkflowDescriptor` object with the current workflow instance details.
     * 2. Constructs an `ExpressionScope` with secrets, workflow descriptor, and runtime descriptor.
     * 3. Evaluates the transformation expression defined in the workflow input using the `JQExpression` evaluator.
     *
     * @return The transformed `JsonNode` representing the instance raw input.
     */
    fun transformInstanceRawInput(): JsonNode {
        val workflowDescriptor = WorkflowDescriptor(
            id = instanceId,
            definition = workflow,
            input = instanceRawInput,
            startedAt = instanceStartedAt,
        )

        expressionScope = ExpressionScope(
            secrets = workflowService.getSecrets(workflow),
            workflow = workflowDescriptor,
            runtime = RuntimeDescriptor,
        )

        return JQExpression.eval(
            instanceRawInput,
            JsonUtils.fromValue(workflow.input.from.get()),
            expressionScope.toScope()
        )
    }

    fun runTask(task: TaskRequest): TaskRequest {
        TODO()
    }

//    fun runCurrentTask(workflow: Workflow, instance: InstanceRequest, taskRequest: TaskRequest): TaskRequest {
//        // Get current task based on position
//        val currentTask = workflow.`do`.getOrNull(request.nextTaskPosition?.let { it.toInt() } ?: 0)
//            ?: throw IllegalStateException("Task at position ${request.nextTaskPosition} not found in workflow ${workflow.document.name}:${workflow.document.version}")
//
//        // Create task instanceContext
//        val taskContext = TaskContext(
//            task = currentTask,
//            instanceRawInput = request.instanceRawInput,
//            position = request.nextTaskPosition?.let { WorkflowPosition(it) } ?: WorkflowPosition("/do/0")
//        )
//
//        // Execute current task
//        val taskOutput = workflowService.executeTask(taskContext, workflowContext)
//
//        // Get next task based on flow directive
//        val nextTask = currentTask.then?.let { nextTaskName ->
//            workflow.do.find { it.name == nextTaskName }
//        } ?: workflow.do.getOrNull(workflow.do.indexOf(currentTask) + 1)
//
//        if (nextTask != null) {
//            // Send message for next task
//            val nextTaskPosition = WorkflowPosition("/do/${workflow.do.indexOf(nextTask)}")
//            workflowService.sendNextTask(
//                workflowName = workflow.name,
//                workflowVersion = workflow.version,
//                instanceId = request.instanceId.toString(),
//                nextTaskRawInput = taskOutput,
//                nextTaskPosition = nextTaskPosition
//            )
//        } else {
//            // Transform and validate workflow output
//            val scope = ExpressionScope(
//                instanceContext = mapOf("instanceContext" to workflowContext.currentContext),
//                input = taskOutput,
//                output = null,
//                secrets = workflowDescriptor.secrets,
//                workflow = workflowDescriptor,
//                runtime = RuntimeDescriptor
//            ).toScope()
//
//            val transformedOutput = workflow.output?.`as`?.let { expr ->
//                JQExpression.eval(taskOutput, JsonUtils.fromValue(expr), scope)
//            } ?: taskOutput
//
//            workflow.output?.schema?.let { schema ->
//                SchemaValidator.validate(transformedOutput, schema)
//            }
//
//            logger.info("Workflow instance {}:{} ({}) completed", workflow.name, workflow.version, request.instanceId)
//        }
//    }

    /**
     * Executes a task and handles workflow continuation.
     *
     * @param taskContext The instanceContext of the task to execute
     * @param workflowContext The instanceContext of the workflow
     * @return The output of the task
     */
//    suspend fun executeTask(
//        taskContext: TaskContext,
//        workflowContext: WorkflowContext,
//        secrets: Map<String, JsonNode>
//    ): JsonNode {
//        val task = taskContext.task
//        val workflow = workflowContext.workflowDescriptor.definition
//
//        // Execute the task
//        val taskOutput = taskService.executeTask(taskContext, workflowContext, secrets)
//
//        // Get next task based on flow directive
//        val nextTask = task.then?.let { nextTaskName ->
//            workflow.`do`.find { it.name == nextTaskName }
//        } ?: workflow.`do`.getOrNull(workflow.do.indexOf(task) + 1)
//
//        if (nextTask != null) {
//            // Send message for next task
//            val nextTaskPosition = WorkflowPosition("/do/${workflow.`do`.indexOf(nextTask)}")
//            sendNextTask(
//                workflowName = workflow.document.name,
//                workflowVersion = workflow.document.version,
//                instanceId = workflowContext.instanceId,
//                nextTaskRawInput = taskOutput,
//                nextTaskPosition = nextTaskPosition
//            )
//        } else {
//            // Transform and validate workflow output
//            val scope = ExpressionScope(
//                instanceContext = mapOf("instanceContext" to workflowContext.currentContext),
//                input = taskOutput,
//                output = null,
//                secrets = workflowContext.workflowDescriptor.secrets,
//                workflow = workflowContext.workflowDescriptor,
//                runtime = RuntimeDescriptor
//            ).toScope()
//
//            val transformedOutput = workflow.output?.`as`?.let { expr ->
//                JQExpression.eval(taskOutput, JsonUtils.fromValue(expr.get()), scope)
//            } ?: taskOutput
//
//            workflow.output?.schema?.let { schema ->
//                SchemaValidator.validate(transformedOutput, schema)
//            }
//
//            logger.info("Workflow instance {}:{} ({}) completed", workflow.document.name, workflow.document.version, workflowContext.instanceId)
//        }
//
//        return taskOutput
//    }


}
