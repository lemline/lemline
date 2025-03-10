package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.ExpressionScope
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.TaskDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.messaging.TaskRequest
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.TaskPosition
import com.lemline.swruntime.tasks.TaskToken.*
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import jakarta.inject.Inject
import java.time.Instant

class WorkflowInstance(
    val workflowName: String,
    val workflowVersion: String,
    val instanceId: String,
    val instanceRawInput: JsonNode,
    var instanceContext: Map<String, JsonNode>,
    val instanceStartedAt: DateTimeDescriptor
) {
    @Inject
    private lateinit var workflowService: WorkflowService

    private val workflow by lazy { workflowService.getWorkflow(workflowName, workflowVersion) }

    private val secrets = workflowService.getSecrets(workflow)

    private val workflowDescriptor = WorkflowDescriptor(
        id = instanceId,
        definition = workflow,
        input = instanceRawInput,
        startedAt = instanceStartedAt,
    )

    private lateinit var currentTaskPosition: TaskPosition

    suspend fun start(): TaskRequest {
        validateInstanceInputSchema()

        val taskRawInput = transformInstanceRawInput()

        // do task position
        currentTaskPosition = TaskPosition.fromString("/do")

        // run the do task
        val task = workflowService.getTask(workflow, "/do")

        TODO()
    }

    suspend fun runTask(rawInput: JsonNode, position: TaskPosition): TaskRequest {
        currentTaskPosition = position

        // Get the task at the current position
        val task = getTaskAtPosition(position)

        // Execute the task
        val taskOutput = executeTask(position, task, rawInput)

        // Determine next task position
        val nextPosition = determineNextTaskPosition(task, position)

        // Handle workflow output transformation if this is the last task
        val finalOutput = if (nextPosition == null) {
            workflow.output?.`as`?.let { expr ->
                val scope = ExpressionScope(
                    context = instanceContext,
                    workflow = workflowDescriptor,
                    runtime = RuntimeDescriptor,
                    secrets = secrets
                ).toScope()
                val transformedOutput = JQExpression.eval(taskOutput, JsonUtils.fromValue(expr.get()), scope)

                // Validate final output if schema provided
                workflow.output?.schema?.let { schema ->
                    SchemaValidator.validate(transformedOutput, schema)
                }
                transformedOutput
            } ?: taskOutput
        } else taskOutput

        // Create task request for next step or workflow completion
        return TaskRequest(
            rawInput = finalOutput,
            position = nextPosition?.jsonPointer() ?: ""
        )
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

        val expressionScope = ExpressionScope(
            secrets = secrets,
            workflow = workflowDescriptor,
            runtime = RuntimeDescriptor,
        )

        return JQExpression.eval(instanceRawInput, workflow.input.from, expressionScope)
    }

    private suspend fun executeTask(
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
            name = position.last,
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

    private suspend fun executeDoTask(task: DoTask, input: JsonNode): JsonNode {
        var currentInput = input
        for (taskItem in task.`do`) {
            //currentInput = executeTask(taskItem.toTask(), currentInput)
        }
        return currentInput
    }

    private suspend fun executeEmitTask(task: EmitTask, input: JsonNode): JsonNode {
        // Implement event emission logic
        TODO("Implement event emission")
    }

    private suspend fun executeForTask(task: ForTask, input: JsonNode): JsonNode {
        // Implement iteration logic
        TODO("Implement for loop execution")
    }

    private suspend fun executeForkTask(task: ForkTask, input: JsonNode): JsonNode {
        // Implement parallel execution logic
        TODO("Implement parallel execution")
    }

    private suspend fun executeListenTask(task: ListenTask, input: JsonNode): JsonNode {
        // Implement event listening logic
        TODO("Implement event listening")
    }

    private suspend fun executeRaiseTask(task: RaiseTask, input: JsonNode): JsonNode {
        // Implement error raising logic
        TODO("Implement error raising")
    }

    private suspend fun executeRunTask(task: RunTask, input: JsonNode): JsonNode {
        // Implement container/script/shell execution logic
        TODO("Implement run execution")
    }

    private suspend fun executeSetTask(task: SetTask, input: JsonNode): JsonNode {
        // Implement context setting logic
        TODO("Implement context setting")
    }

    private suspend fun executeSwitchTask(task: SwitchTask, input: JsonNode): JsonNode {
        // Implement conditional branching logic
        TODO("Implement switch execution")
    }

    private suspend fun executeTryTask(task: TryTask, input: JsonNode): JsonNode {
        // Implement try-catch-finally logic
        TODO("Implement try-catch execution")
    }

    private suspend fun executeWaitTask(task: WaitTask, input: JsonNode): JsonNode {
        // Implement waiting logic
        TODO("Implement wait execution")
    }

    private suspend fun executeHttpCall(task: CallHTTP, input: JsonNode): JsonNode {
        TODO("Implement HTTP call")
    }

    private suspend fun executeGrpcCall(task: CallGRPC, input: JsonNode): JsonNode {
        TODO("Implement gRPC call")
    }

    private suspend fun executeOpenApiCall(task: CallOpenAPI, input: JsonNode): JsonNode {
        TODO("Implement OpenAPI call")
    }

    private suspend fun executeAsyncApiCall(task: CallAsyncAPI, input: JsonNode): JsonNode {
        TODO("Implement AsyncAPI call")
    }

    private suspend fun executeFunctionCall(task: CallFunction, input: JsonNode): JsonNode {
        TODO("Implement custom function call")
    }

    /**
     * Determines the next task position based on the current task and workflow definition.
     * Follows the DSL specification for task flow:
     * 1. If task has a 'then' directive, use it to find the next task or handle special directives:
     *    - 'continue': proceeds to next task in sequence
     *    - 'end': ends the workflow
     *    - 'exit': exits the current scope
     *    - task name: jumps to the specified task in the same scope
     * 2. Otherwise, look for the next task in sequence
     * 3. If no next task exists, the workflow ends
     *
     * @param currentTask The current task that was just executed
     * @param currentPosition The position of the current task
     * @return The next task position, or null if the workflow should end
     */
    private fun determineNextTaskPosition(currentTask: TaskBase, currentPosition: TaskPosition): TaskPosition? {
        // Check for explicit 'then' directive
        currentTask.then?.get()?.let { nextTaskName ->
            val directive = when (nextTaskName) {
                is TextNode -> nextTaskName.textValue()
                else -> nextTaskName.toString()
            }

            // Handle special flow directives
            when (directive.lowercase()) {
                "continue" -> {
                    // Do nothing
                }

                "end" -> {
                    // Explicitly end the workflow
                    return null
                }

                "exit" -> {
                    // Exit the current scope by getting the next task after the parent container
                    val scopePosition = currentPosition.parent ?: return null
                    val containerPosition = scopePosition.parent ?: return null
                    return determineNextTaskPosition(
                        getTaskAtPosition(scopePosition),
                        scopePosition
                    )
                }

                else -> {
                    // Try to find task with matching name in the same scope
                    return findTaskPositionByName(directive, currentPosition.parent ?: return null)
                }
            }
        }

        // If no 'then' directive, get next task in sequence
        return when {
            // If we're in a sequence (do/catch/finally), get next in sequence
            currentPosition.last.toIntOrNull() != null -> {
                val parentPosition = currentPosition.parent ?: return null
                val currentIndex = currentPosition.last.toInt()
                val taskList = getTaskListAtPosition(parentPosition)

                if (currentIndex + 1 < taskList.size) {
                    // Next task in sequence
                    parentPosition.addIndex(currentIndex + 1)
                } else {
                    // End of sequence, go back to parent's next task
                    determineNextTaskPosition(
                        getTaskAtPosition(parentPosition),
                        parentPosition
                    )
                }
            }
            // If we're at a container task (do/try/fork/etc), enter its first task
            isContainerTask(currentTask) -> {
                getFirstTaskInContainer(currentTask, currentPosition)
            }
            // Otherwise, we're done with this branch
            else -> null
        }
    }

    /**
     * Finds a task position by name within a given scope.
     */
    private fun findTaskPositionByName(taskName: String, scopePosition: TaskPosition): TaskPosition? {
        val taskList = getTaskListAtPosition(scopePosition)
        val index = taskList.indexOfFirst { it.name == taskName }
        return if (index >= 0) scopePosition.addIndex(index) else null
    }

    /**
     * Gets the list of tasks at a given position in the workflow.
     */
    private fun getTaskListAtPosition(position: TaskPosition): List<TaskItem> {
        return when (val task = getTaskAtPosition(position)) {
            is DoTask -> task.`do`
            is TryTask -> when (position.last) {
                TRY.token -> task.`try`
                CATCH.token -> task.catch?.`do` ?: emptyList()
                else -> emptyList()
            }

            is ForkTask -> task.fork.branches.toList()
            else -> emptyList()
        }
    }

    /**
     * Gets the first task in a container task (do/try/fork/etc).
     */
    private fun getFirstTaskInContainer(task: TaskBase, position: TaskPosition): TaskPosition? {
        return when (task) {
            is DoTask -> position.addIndex(0)
            is TryTask -> position.addToken(TRY).addIndex(0)
            is ForkTask -> position.addToken(FORK).addIndex(0).addToken(DO).addIndex(0)
            else -> null
        }
    }

    /**
     * Checks if a task is a container that can hold other tasks.
     */
    private fun isContainerTask(task: TaskBase): Boolean {
        return when (task) {
            is DoTask,
            is TryTask,
            is ForkTask -> true

            else -> false
        }
    }

    /**
     * Gets the task at the specified position in the workflow.
     */
    private fun getTaskAtPosition(position: TaskPosition): TaskBase {
        return workflowService.getTask(workflow, position.jsonPointer())
    }
}