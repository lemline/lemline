// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.errors.WorkflowError
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.execution.models.StepResult
import com.lemline.core.execution.state.NodeState
import com.lemline.core.execution.state.Scope
import com.lemline.core.execution.state.merge
import com.lemline.core.expressions.JQExpression
import com.lemline.core.nodes.Node
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.TaskBase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

@ExperimentalTime
abstract class NodeProcessor<T : TaskBase, S : NodeState>(
    val node: Node<T>
) {
    val logger = logger()

    // ========================================
    // Type Specific Actions
    // ========================================

    /**
     * Create the initial state for this node.
     * Subclasses override to create their specific state type.
     *
     * @param transformedInput Transformed input dataset
     * @param scope Expression arguments
     */
    abstract fun createState(transformedInput: JsonElement, scope: Scope): S

    /**
     * Execute node action (for activity tasks).
     *
     * Flow tasks return the input unchanged (no action).
     * Activity tasks perform their action and return the result.
     * Subclasses override this for specific action implementations.
     *
     * @param transformedInput Transformed input for action execution
     * @param scope complete Scope
     * @return Raw output from action
     * @throws com.lemline.core.errors.WorkflowException if action execution fails
     */
    open suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement = transformedInput

    /**
     * Determine:
     * - the updated state of the current node
     * - the next node (parent or child)
     * - the flow directive for the parent (if any)
     *
     * The default implementation returns (null, parent, flowDirective), which is the implementation for a leaf (activity, switch, ...)
     *
     * @param state Type-safe state for this node
     * @param dataset Current dataset
     * @param nodeName Optional node name for goto directives
     * @param scope Scope
     */
    open fun getNextStepInfo(
        state: S,
        dataset: JsonElement,
        nodeName: String? = null,
        scope: Scope,
    ): Triple<NodeState?, Node<*>?, FlowDirective?> =
        Triple(null, node.parent, getFlowDirective())

    // ========================================
    // Entering a Node for the first time
    // ========================================

    suspend fun enterFromParent(rawInput: JsonElement, parentScope: Scope): StepResult {
        // Create execution context
        val now = Clock.System.now()

        var taskContext = TaskContext(startedAt = now)

        // if this node is conditional, check if it should be executed, if not return to parent
        if (!checkIf(rawInput, parentScope.merge(taskContext.toScope(node)))) return StepResult(
            next = node.parent,
            dataset = rawInput,
            stateUpdates = emptyMap(),
            flowDirective = null  // Continue to next sibling
        )

        // Validate input against schema (throws ValidationException)
        validateInput(rawInput)

        // Update context with raw input
        taskContext = taskContext.copy(rawInput = rawInput)

        // Apply input transformation (throws ExpressionException)
        val transformedInput = transformInput(rawInput, parentScope.merge(taskContext.toScope(node)))

        // Update context with transformed input
        taskContext = taskContext.copy(transformedInput = transformedInput)

        // state creation
        val state = createState(transformedInput, parentScope.merge(taskContext.toScope(node)))

        // get the next node and an updated state for the current node
        return continueTo(state, transformedInput, parentScope, taskContext)
    }

    // ========================================
    // ReEntering a Control flow Node from a child
    // ========================================

    // here we process the current node, knowing that we come from a child node that output dataset and flow directive
    suspend fun enterFromChild(
        state: S,
        flowDirective: FlowDirective?,
        datasetFromChild: JsonElement,
        parentScope: Scope
    ): StepResult {

        return when (val directive = flowDirective?.get()) {
            is FlowDirectiveEnum -> when (directive) {
                // END: Workflow complete - recursive unwinding
                FlowDirectiveEnum.END -> continueToEnd(datasetFromChild)
                // EXIT: exit current node
                FlowDirectiveEnum.EXIT -> continueToParent(datasetFromChild, getFlowDirective(), parentScope, null)
                // CONTINUE: continue
                FlowDirectiveEnum.CONTINUE -> continueTo(state, datasetFromChild, parentScope, null)
            }

            // Goto named sibling or null
            is String, null -> continueTo(state, datasetFromChild, parentScope, null, directive)

            else -> throw IllegalArgumentException("Unknown flow directive: $directive")
        }
    }

    // ========================================
    // CONTINUE
    // ========================================

    suspend fun continueTo(
        state: S,
        dataset: JsonElement,
        parentScope: Scope,
        taskContext: TaskContext?,
        nodeName: String? = null
    ): StepResult {
        // get the next node and an updated state for the current node
        val (updatedState, nextNode, currentFlowDirective) = getNextStepInfo(
            state,
            dataset,
            nodeName,
            parentScope.merge(taskContext?.toScope(node))
        )

        // check if we should return to parent
        return when (nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(dataset, currentFlowDirective, parentScope, taskContext)

            // control flows that are not completed (do, for, ...), going to a child
            false -> StepResult(
                nextNode,
                dataset,
                mapOf(node to updatedState),
                null
            )
        }
    }

    // ========================================
    // EXIT
    // ========================================

    internal suspend fun continueToParent(
        dataset: JsonElement,
        currentFlowDirective: FlowDirective?,
        parentScope: Scope,
        taskContext: TaskContext?
    ): StepResult {
        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = execute(dataset, parentScope.merge(taskContext?.toScope(node)))

        // Update context with raw output
        val outputContext = taskContext?.copy(rawOutput = rawOutput)

        // Apply output transformation (throws ExpressionException)
        val transformedOutput = transformOutput(rawOutput, parentScope.merge(outputContext?.toScope(node)))

        // Validate output (throws ValidationException)
        validateOutput(transformedOutput)

        return StepResult(
            node.parent,
            transformedOutput,
            mapOf(node to null),
            currentFlowDirective
        )
    }

    // ========================================
    // END
    // ========================================

    internal fun continueToEnd(dataset: JsonElement) = StepResult(
        next = node.parent,
        dataset = dataset,
        stateUpdates = mapOf(node to null), // clear the state of the current node
        flowDirective = FlowDirective().withFlowDirectiveEnum(FlowDirectiveEnum.END)  // Pass END up the chain
    )

    // ========================================
    // Instance Methods (Scope-Dependent Operations)
    // ========================================

    /**
     * Check if condition for conditional execution.
     *
     * Evaluates the task's `if` expression using scope for context.
     * If no `if` is defined, returns true (always execute).
     *
     * @param rawInput Input dataset for expression evaluation
     * @param scope Expression arguments
     * @return true if task should execute, false to skip
     */
    fun checkIf(rawInput: JsonElement, scope: Scope): Boolean {
        val ifCondition = node.task.`if` ?: return true
        return evalBoolean(rawInput, ifCondition, ".if", scope)
    }

    /**
     * Validate input against schema.
     *
     * @param rawInput Input dataset to validate
     * @throws com.lemline.core.errors.WorkflowException if validation fails
     */
    private fun validateInput(rawInput: JsonElement) {
        node.task.input?.schema?.let { schema ->
            validate(rawInput, schema)
        }
    }

    /**
     * Transform input using input.from expression.
     *
     * Evaluates the task's `input.from` expression to transform the input dataset.
     * If no `input.from` is defined, returns dataset unchanged.
     *
     * @param rawInput Input dataset from parent
     * @param scope Expression arguments
     * @return Transformed input
     * @throws com.lemline.core.errors.WorkflowException if evaluation fails
     */
    private fun transformInput(rawInput: JsonElement, scope: Scope): JsonElement {
        return eval(rawInput, node.task.input?.from, scope)
    }

    /**
     * Transforms the output dataset using the task's `output.as` expression.
     *
     * Evaluates the task's `output.as` expression to produce the transformed output.
     * If no `output.as` is defined, returns the dataset unchanged.
     *
     * @param rawOutput The output dataset to be transformed.
     * @param scope Expression arguments
     * @return The transformed output dataset.
     */
    private fun transformOutput(rawOutput: JsonElement, scope: Scope): JsonElement {
        return eval(rawOutput, node.task.output?.`as`, scope)
    }

    /**
     * Validate output against schema.
     *
     * @param transformedOutput Output dataset to validate
     * @throws com.lemline.core.errors.WorkflowException if validation fails
     */
    fun validateOutput(transformedOutput: JsonElement) {
        node.task.output?.schema?.let { schema ->
            validate(transformedOutput, schema)
        }
    }

    /**
     * Get flow directive from definition.
     *
     * Returns the task's `then` field as a FlowDirective (from SDK).
     *
     * Most tasks return the `then` field from the definition.
     * SwitchTask overrides this to return the selected case's `then` directive.
     *
     * @return Flow directive (from SDK, or null if not specified)
     */
    fun getFlowDirective(): FlowDirective? {
        return node.task.then
    }

    // ========================================
    // Expression Evaluation Helpers
    // ========================================

    private fun validate(data: JsonElement, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        raiseError(VALIDATION, e.message, e.stackTraceToString())
    }

    protected fun evalBoolean(
        data: JsonElement,
        expr: String,
        name: String,
        scope: JsonObject
    ): Boolean = eval(data, expr, scope).let {
        when (it is JsonPrimitive && it.booleanOrNull != null) {
            true -> it.boolean
            false -> raiseError(EXPRESSION, "'$name' expression must be a boolean, but is '$it'")
        }
    }

    internal fun evalList(
        data: JsonElement,
        expr: String,
        name: String,
        scope: JsonObject
    ) = eval(data, expr, scope).let {
        when (it is JsonArray) {
            true -> it.toList()
            false -> raiseError(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
        }
    }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject) = try {
        JQExpression.eval(data, JsonPrimitive(expr), scope, false)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(
        data: JsonElement,
        expr: JsonElement,
        scope: JsonObject,
        force: Boolean = false
    ) = try {
        JQExpression.eval(data, expr, scope, force)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    // ========================================
    // Error Handling
    // ========================================

    internal open fun raiseError(
        type: WorkflowErrorType,
        title: String?,
        details: String? = null,
        status: Int? = null,
    ): Nothing {
        val error = WorkflowError(
            errorType = type,
            title = title ?: "Unknown Error",
            details = details,
            status = status ?: type.defaultStatus,
            position = node.position,
        )
        // Create a minimal old NodeInstance for exception
        // This is a temporary hack until we fully migrate to new execution model
        throw WorkflowExecutionException(error.title ?: error.type, Exception(error.details))
    }
}

/**
 * Workflow execution exception.
 * Used when we can't use the old WorkflowException (which requires old NodeInstance).
 */
class WorkflowExecutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
