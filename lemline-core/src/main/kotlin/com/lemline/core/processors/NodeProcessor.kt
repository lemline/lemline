// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonElement
import com.lemline.common.logger.logger
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.expressions.JQExpression
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.orchestrator.context.TaskContext
import com.lemline.core.orchestrator.context.merge
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.NodeState
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.SubflowInput
import io.serverlessworkflow.api.types.TaskBase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

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
     */
    abstract fun createState(transformedInput: JsonElement, scope: Scope): S

    /**
     * Execute node action (for activity tasks).
     */
    open suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement = transformedInput

    /**
     * Determine:
     * - the updated state after the current node
     * - the next node (parent or child)
     * - the flow directive for the parent (if any)
     *
     * The default implementation is the implementation for a leaf (activity, switch, ...)
     * Other tasks MUST redefine this method.
     */
    open fun getNextStepInfo(
        state: S,
        dataset: JsonElement,
        scope: Scope,
        namedNode: String? = null,
    ) = NextStepInfo(null, node.parent, getFlowDirective())

    // ========================================
    // Entering a Node for the first time
    // ========================================

    @ExperimentalTime
    suspend fun enterFromParent(rawInput: JsonElement, parentScope: Scope): StepResult {
        // Create execution context
        val now = Clock.System.now()

        // create a mutable local task context
        var context = TaskContext(startedAt = now)

        // if this node is conditional, check if it should be executed, if not return to parent
        if (!checkIf(rawInput, mergeScope(parentScope, context))) return StepResult(
            nextNode = node.parent,
            rawInput = rawInput,
            stateUpdates = emptyMap(),
            flowDirective = null  // Continue to next sibling
        )

        // Validate input against schema (throws ValidationException)
        validateInput(rawInput)

        // Update context with raw input
        context = context.copy(rawInput = rawInput)

        // Apply input transformation (throws ExpressionException)
        val transformedInput = transformInput(rawInput, mergeScope(parentScope, context))

        // Update context with transformed input
        context = context.copy(transformedInput = transformedInput)

        // state creation
        val state = createState(transformedInput, mergeScope(parentScope, context))

        // get the next node and an updated state for the current node
        return continueTo(state, transformedInput, parentScope, context)
    }

    // ========================================
    // ReEntering a Control flow Node from a child
    // ========================================

    // here we process the current node, knowing that we come from a child node that output dataset and flow directive
    @ExperimentalTime
    suspend fun enterFromChild(
        state: S,
        flowDirective: FlowDirective?,
        datasetFromChild: JsonElement,
        parentScope: Scope
    ): StepResult = when (val directive = flowDirective?.get()) {
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

    // ========================================
    // CONTINUE
    // ========================================
    @ExperimentalTime
    suspend fun continueTo(
        state: S,
        transformedInput: JsonElement,
        parentScope: Scope,
        taskContext: TaskContext?,
        namedNode: String? = null
    ): StepResult {
        // get the next node and an updated state for the current node
        val (updatedState, nextNode, currentFlowDirective) =
            getNextStepInfo(state, transformedInput, mergeScope(parentScope, taskContext), namedNode)

        // check if we should return to parent
        return when (nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(transformedInput, currentFlowDirective, parentScope, taskContext)

            // control flows that are not completed (do, for, ...), going to a child
            false -> continueToChild(nextNode, transformedInput, updatedState)
        }
    }

    internal fun continueToChild(
        childNode: Node<*>?,
        childRawInput: JsonElement,
        updatedState: NodeState?
    ) = StepResult(
        childNode,
        childRawInput,
        mapOf(node.position to updatedState),
        null
    )

    // ========================================
    // EXIT
    // ========================================
    @ExperimentalTime
    internal suspend fun continueToParent(
        dataset: JsonElement,
        currentFlowDirective: FlowDirective?,
        parentScope: Scope,
        taskContext: TaskContext?
    ): StepResult {
        // create a mutable local task context
        val context = taskContext

        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = execute(dataset, mergeScope(parentScope, context))

        // Complete the task with the raw output
        return completeTask(rawOutput, currentFlowDirective, parentScope, context)
    }

    /**
     * Complete a task with the given raw output.
     * Applies output transformation, validation, and context export.
     * This is separated from continueToParent to allow the orchestrator to
     * complete tasks that were executed externally (e.g., child workflows).
     */
    @ExperimentalTime
    internal fun completeTask(
        rawOutput: JsonElement,
        currentFlowDirective: FlowDirective?,
        parentScope: Scope,
        taskContext: TaskContext?
    ): StepResult {
        var context = taskContext

        // Update context with raw output
        context = context?.copy(rawOutput = rawOutput)

        // Apply output transformation (throws ExpressionException)
        val transformedOutput = transformOutput(rawOutput, mergeScope(parentScope, context))

        // Validate output (throws ValidationException)
        validateOutput(transformedOutput)

        // Update context with transformed output
        context = context?.copy(transformedOutput = transformedOutput)

        // Export to context if export.as is defined (throws ExpressionException or ValidationException)
        val exportedContext = exportToContext(transformedOutput, mergeScope(parentScope, context))

        return StepResult(
            node.parent,
            transformedOutput,
            mapOf(node.position to null),
            currentFlowDirective,
            exportedContext,
        )
    }

    // ========================================
    // END
    // ========================================

    internal fun continueToEnd(dataset: JsonElement) = StepResult(
        nextNode = node.parent,
        rawInput = dataset,
        stateUpdates = mapOf(node.position to null), // clear the state of the current node
        flowDirective = FlowDirective().apply { setFlowDirectiveEnum(FlowDirectiveEnum.END) } // Pass END up the chain
    )

    // ========================================
    // Scope Method
    // ========================================

    @ExperimentalTime
    private fun mergeScope(parentScope: Scope, taskContext: TaskContext?) =
        parentScope.merge(taskContext?.toScope(node))

    // ========================================
    // Instance Methods (Scope-Dependent Operations)
    // ========================================

    /**
     * Check if condition for conditional execution.
     */
    fun checkIf(rawInput: JsonElement, scope: Scope): Boolean {
        val ifCondition = node.task.`if` ?: return true
        return evalBoolean(rawInput, ifCondition, ".if", scope)
    }

    /**
     * Validate input against schema.
     */
    private fun validateInput(rawInput: JsonElement) {
        node.task.input?.schema?.let { schema ->
            validate(rawInput, schema)
        }
    }

    /**
     * Transform input using input.from expression.
     */
    private fun transformInput(rawInput: JsonElement, scope: Scope): JsonElement {
        return eval(rawInput, node.task.input?.from, scope)
    }

    /**
     * Transforms the output dataset using the task's `output.as` expression.
     */
    private fun transformOutput(rawOutput: JsonElement, scope: Scope): JsonElement {
        return eval(rawOutput, node.task.output?.`as`, scope)
    }

    /**
     * Exports data to workflow context using the task's `export.as` expression.
     */
    private fun exportToContext(transformedOutput: JsonElement, scope: Scope): JsonObject? {
        val exportDef = node.task.export ?: return null

        // Evaluate export.as expression with transformed output as input
        val exportedData = evalObject(transformedOutput, exportDef.`as`, "export.as", scope)

        // Validate exported data against schema if provided
        exportDef.schema?.let { schema ->
            validate(exportedData, schema)
        }

        return exportedData
    }

    /**
     * Validate output against schema.
     */
    fun validateOutput(transformedOutput: JsonElement) {
        node.task.output?.schema?.let { schema ->
            validate(transformedOutput, schema)
        }
    }

    /**
     * Get flow directive from definition.
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

    private fun evalObject(
        data: JsonElement,
        expr: ExportAs,
        name: String,
        scope: JsonObject
    ) = eval(data, expr, scope).let {
        when (it is JsonObject) {
            true -> it
            false -> raiseError(EXPRESSION, "'.$name' expression must be an object, but is '$it'")
        }
    }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, exportAs: ExportAs?, scope: JsonObject) =
        exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    internal fun eval(data: JsonElement, subFlowInput: SubflowInput?, scope: JsonObject) =
        subFlowInput?.let { eval(data, it.additionalProperties.toJsonElement(), scope, false) } ?: data

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
        val error = InternalWorkflowException.Error(
            errorType = type,
            title = title ?: "Unknown Error",
            details = details,
            status = status ?: type.defaultStatus,
            position = node.position,
        )
        throw InternalWorkflowException(error)
    }

    /**
     * Retrieves the root task of the current node by traversing up the hierarchy.
     */
    protected fun getRootTask(): RootTask {
        var rootNode: Node<*> = node
        while (rootNode.parent != null) rootNode = rootNode.parent

        if (rootNode.task !is RootTask) throw IllegalStateException("RootNode is not a RootTask! $rootNode")

        return rootNode.task
    }
}

/**
 * Represents the result of determining the next navigation step within a workflow or process.
 *
 * Components:
 * - A `NodeState?`: The updated state of the current node, null indicates node is completed
 * - A `Node<*>?`: The next node to navigate to, null indicates navigation ends
 * - A `FlowDirective?`: Directives influencing the parent execution flow (if any)
 */
data class NextStepInfo(
    val updatedState: NodeState?,
    val nextNode: Node<*>?,
    val flowDirective: FlowDirective?
)
