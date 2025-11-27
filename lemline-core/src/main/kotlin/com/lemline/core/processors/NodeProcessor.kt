// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonElement
import com.lemline.common.logger.logger
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.expressions.JQExpression
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.orchestrator.context.TaskScope
import com.lemline.core.orchestrator.context.merge
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.BaseState
import com.lemline.core.states.RootState
import com.lemline.core.states.TaskStates
import com.lemline.common.values.NodePosition
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.SubflowInput
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.Use
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

@ExperimentalTime
abstract class NodeProcessor<T : TaskBase, S : BaseState>(
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
    abstract fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope): S

    /**
     * Execute node action (for activity tasks).
     */
    open suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
        state: S,
    ): JsonElement = transformedInput

    /**
     * Use object, if any
     */
    val use: Use? by lazy {
        var rootNode: Node<*> = node
        while (rootNode.parent != null) rootNode = rootNode.parent
        if (rootNode.task !is RootTask) throw IllegalStateException("RootNode has no RootTask! $rootNode")

        rootNode.task.use
    }

    /**
     * Update state when re-entering from child.
     * Default: just increment visitCount.
     */
    open fun stateEnterFromChild(
        state: S,
        output: JsonElement,
        scope: Scope,
        nodeName: String? = null
    ): S {
        // Default implementation for leaf nodes - just increment visitCount
        @Suppress("UNCHECKED_CAST")
        return state
    }

    /**
     * Determine where to go next based on current state.
     * Default: go to parent (leaf node behavior - activities, switch, etc.)
     */
    open fun getNextNode(
        state: S,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo {
        return NavigationInfo(
            nextNode = node.parent,
            nextDirective = getFlowDirective()
        )
    }

    // ========================================
    // Entering a Node for the first time
    // ========================================
    suspend fun enterFromParent(rawInput: JsonElement, parentScope: Scope, taskStates: TaskStates): StepResult {
        // Create execution context
        val now = Clock.System.now()

        // create a mutable local task context
        var context = TaskScope(startedAt = now)

        // Validate input against schema (throws ValidationException)
        validateInput(rawInput)

        // Update context with raw input
        context = context.copy(rawInput = rawInput)

        // if this node is conditional, check if it should be executed, if not return to parent
        if (!checkIf(rawInput, mergeScope(parentScope, context))) return StepResult(
            nextNode = node.parent,
            nextInput = rawInput,
            taskStates = taskStates,  // No state changes
            nextDirective = null  // Continue to next sibling
        )

        // Apply input transformation (throws ExpressionException)
        val transformedInput = transformInput(rawInput, mergeScope(parentScope, context))

        // Update context with transformed input
        context = context.copy(transformedInput = transformedInput)

        // state creation
        val state = stateEnterFromParent(transformedInput, mergeScope(parentScope, context))

        // get the next node and an updated state for the current node
        return continueTo(state, transformedInput, parentScope, context, taskStates)
    }

    // ========================================
    // ReEntering a Control flow Node from a child
    // ========================================

    private val FlowDirective?.nodeName
        get() = when (val nodeName = this?.get()) {
            is String -> nodeName
            else -> null
        }

    // here we process the current node, knowing that we come from a child node that output dataset and flow directive
    internal suspend fun enterFromChild(
        state: S,
        flowDirective: FlowDirective?,
        output: JsonElement,
        parentScope: Scope,
        taskStates: TaskStates
    ): StepResult {
        val updatedState = stateEnterFromChild(state, output, parentScope, flowDirective.nodeName)

        return when (val directive = flowDirective?.get()) {
            is FlowDirectiveEnum -> when (directive) {
                // END: Workflow complete - recursive unwinding
                FlowDirectiveEnum.END -> continueToEnd(dataset = output, taskStates = taskStates)
                // EXIT: exit current node
                FlowDirectiveEnum.EXIT -> continueToParent(
                    state = updatedState,
                    transformedInput = output,
                    currentFlowDirective = getFlowDirective(),
                    parentScope = parentScope,
                    taskScope = null,
                    taskStates = taskStates
                )
                // CONTINUE: continue
                FlowDirectiveEnum.CONTINUE -> continueTo(
                    state = updatedState,
                    transformedInput = output,
                    parentScope = parentScope,
                    taskScope = null,
                    taskStates = taskStates
                )
            }

            // Goto named sibling or null
            is String, null -> continueTo(
                state = updatedState,
                transformedInput = output,
                parentScope = parentScope,
                taskScope = null,
                taskStates = taskStates
            )

            else -> throw IllegalArgumentException("Unknown flow directive: $directive")
        }
    }

    // ========================================
    // CONTINUE
    // ========================================
    private suspend fun continueTo(
        state: S,
        transformedInput: JsonElement,
        parentScope: Scope,
        taskScope: TaskScope?,
        taskStates: TaskStates
    ): StepResult {
        // Pure navigation - determine where to go next
        val scope = mergeScope(parentScope, taskScope)
        val navigation = getNextNode(state, transformedInput, scope)

        // check if we should return to parent
        return when (navigation.nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(
                state = state,
                transformedInput = transformedInput,
                currentFlowDirective = navigation.nextDirective,
                parentScope = parentScope,
                taskScope = taskScope,
                taskStates = taskStates
            )

            // control flows that are not completed (do, for, ...), going to a child
            false -> continueToChild(
                childNode = navigation.nextNode,
                childRawInput = transformedInput,
                updatedState = state,
                taskStates = taskStates
            )
        }
    }

    internal fun continueToChild(
        childNode: Node<*>?,
        childRawInput: JsonElement,
        updatedState: BaseState,
        taskStates: TaskStates
    ) = StepResult(
        nextNode = childNode,
        nextInput = childRawInput,
        taskStates = taskStates + (node.position to updatedState),
        nextDirective = null
    )

    // ========================================
    // EXIT
    // ========================================
    internal suspend fun continueToParent(
        state: S,
        transformedInput: JsonElement,
        currentFlowDirective: FlowDirective?,
        parentScope: Scope,
        taskScope: TaskScope?,
        taskStates: TaskStates
    ): StepResult {
        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = execute(transformedInput, mergeScope(parentScope, taskScope), state)

        // Complete the task with the raw output
        return completeTask(rawOutput, currentFlowDirective, parentScope, taskScope, taskStates)
    }

    /**
     * Complete a task with the given raw output.
     * Applies output transformation, validation, and context export.
     * This is separated from continueToParent to allow the orchestrator to
     * complete tasks that were executed externally (e.g., child workflows).
     */
    internal fun completeTask(
        rawOutput: JsonElement,
        currentFlowDirective: FlowDirective?,
        parentScope: Scope,
        taskScope: TaskScope?,
        taskStates: TaskStates
    ): StepResult {
        var context = taskScope

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

        // Build the updated task states: remove current node's state (but never root), update context if needed
        val updatedStates = taskStates.toMutableMap().apply {
            if (node.position != NodePosition.root) {
                remove(node.position)
            }
            if (exportedContext != null) {
                val rootState = this[NodePosition.root] as RootState
                this[NodePosition.root] = rootState.copyWithContext(exportedContext)
            }
        }

        return StepResult(
            nextNode = node.parent,
            nextInput = transformedOutput,
            taskStates = updatedStates,
            nextDirective = currentFlowDirective,
        )
    }

    // ========================================
    // END
    // ========================================

    internal fun continueToEnd(dataset: JsonElement, taskStates: TaskStates) = StepResult(
        nextNode = node.parent,
        nextInput = dataset,
        // Clear the state of the current node (but never remove root state)
        taskStates = if (node.position == NodePosition.root) taskStates else taskStates - node.position,
        nextDirective = FlowDirective().apply { setFlowDirectiveEnum(FlowDirectiveEnum.END) } // Pass END up the chain
    )

    // ========================================
    // Scope Method
    // ========================================

    private fun mergeScope(parentScope: Scope, taskScope: TaskScope?) =
        parentScope.merge(taskScope?.toScope(node))

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
        return inputFrom(rawInput, node.task.input?.from, scope)
    }

    /**
     * Transforms the output dataset using the task's `output.as` expression.
     */
    private fun transformOutput(rawOutput: JsonElement, scope: Scope): JsonElement {
        return outputAs(rawOutput, node.task.output?.`as`, scope)
    }

    /**
     * Exports data to workflow context using the task's `export.as` expression.
     */
    private fun exportToContext(transformedOutput: JsonElement, scope: Scope): JsonObject? {
        val exportDef = node.task.export ?: return null

        // Evaluate export.as expression with transformed output as input
        val exportedData = exportAs(transformedOutput, exportDef.`as`, scope)

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
    // Validation helper
    // ========================================

    private fun validate(data: JsonElement, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        raiseError(VALIDATION, e.message, e.stackTraceToString())
    }

    // ========================================
    // Expression Evaluation Helpers
    // ========================================

    private fun inputFrom(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject): JsonElement =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun outputAs(data: JsonElement, outputAs: OutputAs?, scope: JsonObject): JsonElement =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun exportAs(data: JsonElement, exportAs: ExportAs?, scope: JsonObject): JsonObject =
        (exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data).let {
            when (it is JsonObject) {
                true -> it
                false -> raiseError(EXPRESSION, "'.export.as' expression must be an object, but is '$it'")
            }
        }

    internal fun runWorkflowInput(data: JsonElement, subFlowInput: SubflowInput?, scope: JsonObject): JsonElement =
        subFlowInput?.let { eval(data, it.additionalProperties.toJsonElement(), scope, false) } ?: data

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


    private fun eval(data: JsonElement, expr: String, scope: JsonObject): JsonElement = try {
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
    // Error raising helper
    // ========================================

    internal open fun raiseError(
        type: WorkflowErrorType,
        title: String?,
        details: String? = null,
        status: Int? = null,
    ): Nothing {
        val error = InternalException.Error(
            errorType = type,
            title = title ?: "Unknown Error",
            details = details,
            status = status ?: type.defaultStatus,
            position = node.position,
        )
        throw InternalException(error)
    }
}

/**
 * Represents the navigation decision within a workflow.
 *
 * Components:
 * - A `Node<*>?`: The next node to navigate to, null indicates navigation ends
 * - A `FlowDirective?`: Directives influencing the parent execution flow (if any)
 */
data class NavigationInfo(
    val nextNode: Node<*>?,
    val nextDirective: FlowDirective?
)

/**
 * Represents the result of determining the next navigation step within a workflow or process.
 *
 * Components:
 * - A `NodeState?`: The updated state of the *current* node
 * - A `Node<*>?`: The next node to navigate to, null indicates navigation ends
 * - A `FlowDirective?`: Directives influencing the parent execution flow (if any)
 *
 * @deprecated Use NavigationInfo for navigation decisions and updateState() for state updates
 */
@Deprecated("Use NavigationInfo and updateState() separately")
data class NextStepInfo<T : BaseState>(
    val updatedState: T,
    val nextNode: Node<*>?,
    val nextDirective: FlowDirective?
)
