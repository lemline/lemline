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
import com.lemline.core.execution.state.ExprArgs
import com.lemline.core.execution.state.NoState
import com.lemline.core.execution.state.NodeState
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
    val node: Node<T>,
    val exprArgs: ExprArgs
) {
    val logger = logger()

    // ========================================
    // Scope Building (for Expression Evaluation)
    // ========================================

    val nodeDescriptor = NodeDescriptor.from(node)

    // ========================================
    // Type Specific Actions
    // ========================================

    /**
     * Create initial state for this node.
     * Subclasses override to create their specific state type.
     */
    abstract fun createState(dataset: JsonElement): S

    /**
     * Execute node action (for activity tasks).
     *
     * Flow tasks return the input unchanged (no action).
     * Activity tasks perform their action and return the result.
     * Subclasses override this for specific action implementations.
     *
     * @param input Transformed input for action execution
     * @return Raw output from action
     * @throws WorkflowException if action execution fails
     */
    open suspend fun execute(input: JsonElement): JsonElement = input

    /**
     * Determine:
     * - the updated state of the current node
     * - the next node (parent or child)
     * - the flow directive for the parent (if any)
     *
     * The default implementation returns (null, parent, flowDirective), which is the implementation for a leaf (activity, switch, ...)
     *
     * @param state Type-safe state for this node
     */
    open fun getNextStepInfo(
        state: S,
        dataset: JsonElement,
        nodeName: String? = null
    ): Triple<NodeState?, Node<*>?, FlowDirective?> =
        Triple(null, node.parent, getFlowDirective())

    // ========================================
    // Entering a Node for the first time
    // ========================================

    suspend fun enterFromParent(dataset: JsonElement): StepResult {

        // if this node is conditional, check if it should be executed, if not return to parent
        if (!checkIf(dataset)) return StepResult(
            next = node.parent,
            dataset = dataset,
            stateUpdates = emptyMap(),
            flowDirective = null  // Continue to next sibling
        )

        // starting now
        val now = Clock.System.now()

        // Set startedAt for task descriptor (for scope building)
        nodeDescriptor.setStartedAt(now)

        // Set raw input (for scope building)
        nodeDescriptor.rawInput = dataset

        // Validate input against schema (throws ValidationException)
        validateInput(dataset)

        // Apply input transformation (throws ExpressionException)
        val transformedInput = transformInput(dataset)

        // Set transformed input (for scope building)
        nodeDescriptor.transformedInput = transformedInput

        // state creation
        val state = createState(transformedInput)

        // get the next node and an updated state for the current node
        return continueTo(state, transformedInput)
    }

    // ========================================
    // ReEntering a Control flow Node from a child
    // ========================================

    // here we process the current node, knowing that we come from a child node that output dataset and flow directive
    suspend fun enterFromChild(
        state: S,
        flowDirective: FlowDirective?,
        datasetFromChild: JsonElement
    ): StepResult =
        when (val directive = flowDirective?.get()) {
            is FlowDirectiveEnum -> when (directive) {
                // END: Workflow complete - recursive unwinding
                FlowDirectiveEnum.END -> continueToEnd(datasetFromChild)
                // EXIT: exit current node
                FlowDirectiveEnum.EXIT -> continueToParent(datasetFromChild, getFlowDirective())
                // CONTINUE: continue
                FlowDirectiveEnum.CONTINUE -> continueTo(state, datasetFromChild)
            }

            // Goto named sibling or null
            is String, null -> continueTo(state, datasetFromChild, directive)

            else -> throw IllegalArgumentException("Unknown flow directive: $directive")
        }

    // ========================================
    // CONTINUE
    // ========================================

    suspend fun continueTo(state: S, dataset: JsonElement, nodeName: String? = null): StepResult {
        // get the next node and an updated state for the current node
        val (updatedState, nextNode, currentFlowDirective) = getNextStepInfo(state, dataset, nodeName)

        // check if we should return to parent
        return when (nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(dataset, currentFlowDirective)

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

    internal suspend fun continueToParent(dataset: JsonElement, currentFlowDirective: FlowDirective?): StepResult {
        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = execute(dataset)

        // Set raw input (for scope building)
        nodeDescriptor.rawOutput = rawOutput

        // Apply output transformation (throws ExpressionException)
        val transformedOutput = transformOutput(rawOutput)

        // Set transformed output (for scope building)
        nodeDescriptor.transformedOutput = transformedOutput

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
     * @param dataset Input dataset for expression evaluation
     * @return true if task should execute, false to skip
     */
    fun checkIf(dataset: JsonElement): Boolean {
        val ifCondition = node.task.`if` ?: return true
        return evalBoolean(dataset, ifCondition, ".if", exprArgs)
    }

    /**
     * Validate input against schema.
     *
     * @param dataset Input dataset to validate
     * @throws WorkflowException if validation fails
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
     * @param dataset Input dataset from parent
     * @return Transformed input
     * @throws WorkflowException if evaluation fails
     */
    private fun transformInput(rawInput: JsonElement): JsonElement {
        return eval(rawInput, node.task.input?.from)
    }

    /**
     * Transforms the output dataset using the task's `output.as` expression.
     *
     * Evaluates the task's `output.as` expression to produce the transformed output.
     * If no `output.as` is defined, returns the dataset unchanged.
     *
     * @param dataset The output dataset to be transformed.
     * @return The transformed output dataset.
     */
    private fun transformOutput(rawOutput: JsonElement): JsonElement {
        return eval(rawOutput, node.task.output?.`as`)
    }

    /**
     * Validate output against schema.
     *
     * @param output Output dataset to validate
     * @throws WorkflowException if validation fails
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
        scope: JsonObject = this.exprArgs
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
        scope: JsonObject = this.exprArgs
    ) = eval(data, expr, scope).let {
        when (it is JsonArray) {
            true -> it.toList()
            false -> raiseError(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
        }
    }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject = this.exprArgs) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject = this.exprArgs) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject = this.exprArgs) = try {
        JQExpression.eval(data, JsonPrimitive(expr), scope, false)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(
        data: JsonElement,
        expr: JsonElement,
        scope: JsonObject = this.exprArgs,
        force: Boolean = false
    ) = try {
        JQExpression.eval(data, expr, scope, force)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    // ========================================
    // Flow Directive Application
    // ========================================

    /**
     * Apply flow directive to update state.
     *
     * This method updates the node's mutable state based on the flow directive.
     * Having this in NodeInstance (instead of NodeState) allows access to the
     * immutable node definition for metadata like child names.
     *
     * Default implementation does nothing (for activity tasks that don't navigate).
     * Flow tasks override this to implement their navigation logic.
     *
     * @param gotoTarget Target task name for goto, or null for default continue
     */
    open fun applyFlowDirective(gotoTarget: String?) {
        // Default: do nothing (activity tasks)
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
