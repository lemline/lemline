package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.ExpressionScope
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.messaging.TaskRequest
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.TaskPosition
import com.lemline.swruntime.tasks.TaskToken.*
import com.lemline.swruntime.tasks.execute.executeTask
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import jakarta.inject.Inject

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

    internal val secrets = workflowService.getSecrets(workflow)

    internal val workflowDescriptor = WorkflowDescriptor(
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
            transformInstanceRawOutput(taskOutput).also {
                // Validate the final output schema
                validateInstanceOutputSchema(it)
            }
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

    fun validateInstanceOutputSchema(output: JsonNode) {
        workflow.output?.schema?.let { schema -> SchemaValidator.validate(output, schema) }
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

    /**
     * Transforms the instance raw output using the workflow's output transformation expression.
     *
     * This method performs the following steps:
     * 1. Creates an `ExpressionScope` object with the current workflow instance details.
     * 2. Constructs an `ExpressionScope` with secrets, workflow descriptor, and runtime descriptor.
     * 3. Evaluates the transformation expression defined in the workflow output using the `JQExpression` evaluator.
     *
     * @param lastTaskOutput The `JsonNode` representing the last task output.
     * @return The transformed `JsonNode` representing the instance raw output.
     */
    private fun transformInstanceRawOutput(lastTaskOutput: JsonNode): JsonNode {
        val scope = ExpressionScope(
            context = instanceContext,
            workflow = workflowDescriptor,
            runtime = RuntimeDescriptor,
            secrets = secrets
        )
        return JQExpression.eval(lastTaskOutput, workflow.output?.`as`, scope)
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
                    // Default behavior: proceed to next task in sequence
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
        val nextIndex = currentPosition.parent?.last?.toIntOrNull()

        return when {
            // If we're in a sequence (do/try/catch/), get next in sequence
            currentPosition.parent?.last?.toIntOrNull() != null -> {
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
        TODO()
    }
}