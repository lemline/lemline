package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.ExpressionScope
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.TaskDescriptor
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.TaskPosition
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import java.time.Instant

internal suspend fun WorkflowInstance.executeTask(
    position: TaskPosition,
    task: TaskBase,
    rawInput: JsonNode
): JsonNode {
    // 1. Validate task input if schema is provided
    task.input?.schema?.let { schema ->
        SchemaValidator.validate(rawInput, schema)
    }

    // 2. Transform task input using `input.from` expression if provided
    val taskDescriptor = TaskDescriptor(
        name = position.last!!,
        reference = position.jsonPointer(),
        definition = JsonUtils.fromValue(task),
        input = rawInput,
        output = null,
        startedAt = DateTimeDescriptor.from(Instant.now())
    )

    val expressionScope = ExpressionScope(
        context = instanceContext,
        secrets = secrets,
        task = taskDescriptor,
        workflow = workflowDescriptor,
        runtime = RuntimeDescriptor,
    )

    val taskInput = JQExpression.eval(rawInput, task.input?.from, expressionScope)

    // 3. Test If task should be executed
    task.`if`?.let { ifString ->
        val shouldExecuteTask = JQExpression.eval(taskInput, ifString, expressionScope).let {
            if (it.isBoolean) it.asBoolean() else throw IllegalArgumentException("Task condition must evaluate to a boolean")
        }
        if (!shouldExecuteTask) return rawInput
    }

    // 4. Execute task based on its type
    val taskRawOutput = when (task) {
        is CallHTTP -> executeHttpCall(task, taskInput)
        is CallGRPC -> executeGrpcCall(task, taskInput)
        is CallOpenAPI -> executeOpenApiCall(task, taskInput)
        is CallAsyncAPI -> executeAsyncApiCall(task, taskInput)
        is CallFunction -> executeFunctionCall(task, taskInput)
        is DoTask -> executeDoTask(task, taskInput)
        is EmitTask -> executeEmitTask(task, taskInput)
        is ForTask -> executeForTask(task, taskInput)
        is ForkTask -> executeForkTask(task, taskInput)
        is ListenTask -> executeListenTask(task, taskInput)
        is RaiseTask -> executeRaiseTask(task, taskInput)
        is RunTask -> executeRunTask(task, taskInput)
        is SetTask -> executeSetTask(task, taskInput)
        is SwitchTask -> executeSwitchTask(task, taskInput)
        is TryTask -> executeTryTask(task, taskInput)
        is WaitTask -> executeWaitTask(task, taskInput)
        else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.name}")
    }

    // 5. Transform task output using output.as expression if provided
    val taskOutput = JQExpression.eval(taskRawOutput, task.output?.`as`, expressionScope)

    // 6. Validate task output if schema is provided
    task.output?.schema?.let { schema ->
        SchemaValidator.validate(taskOutput, schema)
    }

    // 7. Update workflow context using export.as expression if provided
    taskDescriptor.output = taskRawOutput

    // 8. export as new context
    task.export?.`as`?.let { exportAs ->
        val newContext = when (val context = JQExpression.eval(taskOutput, exportAs, expressionScope)) {
            is ObjectNode -> context
            else -> throw IllegalArgumentException("Exported context must be an object")
        }

        // 9. Validate exported context if schema is provided
        task.export.schema?.let { schema ->
            SchemaValidator.validate(newContext, schema)
        }

        instanceContext = newContext.fields().asSequence().associate { it.key to it.value }
    }

    // 10. Return task output
    return taskOutput
}