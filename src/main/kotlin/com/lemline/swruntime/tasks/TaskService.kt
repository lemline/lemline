package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.activities.calls.HttpCall
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.ExpressionScope
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.TaskDescriptor
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.workflows.WorkflowContext
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.LoggerFactory
import java.time.Instant

@ApplicationScoped
class TaskService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpCall = HttpCall()

    /**
     * Executes a task according to the DSL specification.
     *
     * @param taskContext The instanceContext of the task to execute
     * @param workflowContext The instanceContext of the workflow
     * @return The transformed output of the task
     */
    suspend fun executeTask(
        taskContext: TaskContext,
        workflowContext: WorkflowContext,
        secrets: Map<String, JsonNode>
    ): JsonNode {
        val task = taskContext.task
        val rawInput = taskContext.rawInput
        val position = taskContext.position

        // 1. Validate Task Input
        task.input?.schema?.let { schema -> SchemaValidator.validate(rawInput, schema) }

        // 2. Transform Task Input
        val taskDescriptor = TaskDescriptor(
            name = taskContext.position.lastComponent!!,
            reference = position.toString(),
            definition = task,
            rawInput = rawInput,
            rawOutput = null,
            startedAt = DateTimeDescriptor.from(Instant.now())
        )

        val scope = ExpressionScope(
            context = mapOf("instanceContext" to workflowContext.currentContext),
            input = rawInput,
            output = null,
            secrets = secrets,
            task = taskDescriptor,
            workflow = workflowContext.workflowDescriptor,
            runtime = RuntimeDescriptor
        ).toScope()

        val transformedInput = task.input?.from?.let { expr ->
            JQExpression.eval(rawInput, JsonUtils.fromValue(expr), scope)
        } ?: rawInput

        // 3. Process the task
        val rawOutput = when (task) {
            // is TaskBase.Call -> executeCall(task, transformedInput)
            else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.simpleName}")
        }

        // 4. Transform Task Output
        val transformedOutput = task.output?.`as`?.let { expr ->
            JQExpression.eval(rawOutput, JsonUtils.fromValue(expr), scope)
        } ?: rawOutput

        // 5. Validate Task Output
        task.output?.schema?.let { schema -> SchemaValidator.validate(transformedOutput, schema) }

        // 6. Update Workflow Context
        val newContext = task.export?.`as`?.let { expr ->
            JQExpression.eval(transformedOutput, JsonUtils.fromValue(expr), scope)
        } ?: workflowContext.currentContext

        // 7. Validate Exported Context
        task.export?.schema?.let { schema -> SchemaValidator.validate(newContext, schema) }

        return transformedOutput
    }

//    private suspend fun executeCall(task: TaskBase.Call, instanceRawInput: JsonNode): JsonNode {
//        return when (task.call) {
//            is TaskBase.Call.Http -> {
//                val httpCall = task.call as TaskBase.Call.Http
//                httpCall.execute(
//                    method = httpCall.method,
//                    endpoint = httpCall.endpoint.uri,
//                    headers = httpCall.endpoint.headers ?: emptyMap(),
//                    body = instanceRawInput,
//                    query = httpCall.endpoint.query ?: emptyMap(),
//                    output = httpCall.output ?: "content",
//                    redirect = httpCall.endpoint.redirect ?: false
//                ).get()
//            }
//            else -> throw IllegalArgumentException("Unsupported call type: ${task.call.javaClass.simpleName}")
//        }
//    }
} 