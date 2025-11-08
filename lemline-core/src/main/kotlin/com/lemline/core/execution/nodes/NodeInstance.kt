// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.errors.WorkflowError
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.execution.state.NodeState
import com.lemline.core.expressions.JQExpression
import com.lemline.core.expressions.scopes.Scope
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.nodes.Node
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

/**
 * Base class for all task instances in the functional execution model.
 *
 * This is the runtime representation of a workflow node that maintains:
 * - Reference to the immutable Node<T> definition
 * - Execution state (separated into immutable + mutable)
 * - Parent/child relationships in the runtime tree
 * - Scope for expression evaluation
 *
 * ## Architecture Pattern
 *
 * Following the existing lemline pattern:
 * - **Node<T>**: Immutable workflow definition (like a class)
 * - **NodeInstance**: Runtime instance with state (like an object)
 *
 * ## Key Differences from Old NodeInstance
 *
 * **Orchestration** (now free functions in ExecutionOrchestrator):
 * - `enter()`: Entry from parent
 * - `reEnter()`: Re-entry from child
 * - `continue()`: Navigation decision
 * - `exitToUp()`: Exit to parent
 *
 * **Instance Methods** (scope-dependent operations, remain here):
 * - `checkIf()`: Evaluate `if` condition
 * - `validateInput()`: Validate input schema
 * - `evaluateInput()`: Transform input with `input.from`
 * - `execute()`: Execute action (for activities)
 * - `evaluateOutput()`: Transform output with `output.as`
 * - `validateOutput()`: Validate output schema
 *
 * **State Management**:
 * - Old: Single NodeState with all fields
 * - New: NodeState<M> with immutable (cached) + mutable (serialized) separation
 *
 * @property node Immutable node definition from workflow DSL
 * @property parent Parent instance in runtime tree (null for root)
 */
@ExperimentalTime
abstract class NodeInstance<T : TaskBase>(
    open val node: Node<T>,
    open val parent: NodeInstance<*>?
) {
    val logger = logger()

    /**
     * Execution state with immutable/mutable separation.
     * Subclasses provide specific state types (DoTaskState, ForTaskState, etc.)
     */
    abstract var state: NodeState<*>

    /**
     * Additional variables for this scope (e.g., $item, $index from ForTask).
     * These are node-specific variables added to the scope hierarchy.
     */
    internal var variables = JsonObject(mapOf())

    /**
     * Child instances (runtime tree, built from node.children).
     * Initialized by subclasses during construction.
     */
    lateinit var children: List<NodeInstance<*>>

    /**
     * Root instance of the workflow.
     */
    internal val rootInstance: RootNodeInstance by lazy {
        when (this) {
            is RootNodeInstance -> this
            else -> parent?.rootInstance
                ?: raiseError(com.lemline.core.errors.WorkflowErrorType.RUNTIME, "$this is not root, but does not have a parent")
        }
    }

    // ========================================
    // Scope Building (for Expression Evaluation)
    // ========================================

    /**
     * Raw input for this task (before transformation).
     * Set by parent when entering or re-entering this node.
     */
    internal var rawInput: JsonElement? = null

    /**
     * Raw output from this task's action (before transformation).
     * Set after execute() completes.
     */
    internal var rawOutput: JsonElement? = null

    /**
     * Task descriptor for scope building.
     * Computed on demand from current state.
     */
    private val taskDescriptor: TaskDescriptor
        get() = TaskDescriptor(
            name = node.name,
            reference = node.reference,
            definition = node.definition,
            startedAt = state.startedAt?.let {
                LemlineJson.encodeToElement(DateTimeDescriptor.from(it.toJavaInstant()))
            },
            input = rawInput,
            output = rawOutput,
        )

    /**
     * Scope used during expression evaluation.
     *
     * Built hierarchically by merging:
     * 1. Node-specific variables (e.g., $item, $index from ForTask)
     * 2. Current task descriptor ($task.*, $input, $output)
     * 3. Parent scope (recursively)
     *
     * This creates a scope chain where inner tasks can access outer task variables.
     */
    internal open val scope: JsonObject
        get() = variables
            // Merge current scope
            .merge(
                Scope(
                    task = taskDescriptor,
                    input = rawInput,
                    output = rawOutput,
                ).toJsonObject(),
            )
            // Recursively merge with parent scope
            .merge(parent?.scope)

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
    open fun checkIf(dataset: JsonElement): Boolean {
        val ifCondition = node.task.`if` ?: return true
        return evalBoolean(dataset, ifCondition, ".if", scope)
    }

    /**
     * Validate input against schema.
     *
     * @param dataset Input dataset to validate
     * @throws WorkflowException if validation fails
     */
    fun validateInput(dataset: JsonElement) {
        node.task.input?.schema?.let { schema ->
            validate(dataset, schema)
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
    fun evaluateInput(dataset: JsonElement): JsonElement {
        return eval(dataset, node.task.input?.from)
    }

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
    abstract suspend fun execute(input: JsonElement): JsonElement

    /**
     * Transform output using output.as expression.
     *
     * Evaluates the task's `output.as` expression to transform the output dataset.
     * If no `output.as` is defined, returns output unchanged.
     *
     * @param output Raw output from action or child
     * @return Transformed output
     * @throws WorkflowException if evaluation fails
     */
    fun evaluateOutput(output: JsonElement): JsonElement {
        return eval(output, node.task.output?.`as`)
    }

    /**
     * Validate output against schema.
     *
     * @param output Output dataset to validate
     * @throws WorkflowException if validation fails
     */
    fun validateOutput(output: JsonElement) {
        node.task.output?.schema?.let { schema ->
            validate(output, schema)
        }
    }

    /**
     * Get flow directive from definition.
     *
     * Returns the task's `then` field as a FlowDirective (from SDK).
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
        scope: JsonObject = this.scope
    ): Boolean = eval(data, expr, scope).let {
        when (it is JsonPrimitive && it.booleanOrNull != null) {
            true -> it.boolean
            false -> raiseError(EXPRESSION, "'$name' expression must be a boolean, but is '$it'")
        }
    }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject = this.scope) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject = this.scope) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject = this.scope) = try {
        JQExpression.eval(data, JsonPrimitive(expr), scope, false)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(
        data: JsonElement,
        expr: JsonElement,
        scope: JsonObject = this.scope,
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

    // ========================================
    // Utility Methods
    // ========================================

    private fun JsonObject.merge(other: JsonObject?): JsonObject {
        val mergedMap = buildMap {
            other?.forEach { put(it.key, it.value) }
            this@merge.forEach { put(it.key, it.value) }
        }
        return JsonObject(mergedMap)
    }
}

/**
 * Root node instance for the workflow.
 * Special instance that doesn't have a parent and manages workflow-level context.
 */
abstract class RootNodeInstance(
    node: Node<out TaskBase>
) : NodeInstance<TaskBase>(node as Node<TaskBase>, null) {
    /**
     * Workflow-level context (exported values from export.as).
     */
    var context: JsonObject = JsonObject(mapOf())
}

/**
 * Workflow execution exception.
 * Used when we can't use the old WorkflowException (which requires old NodeInstance).
 */
class WorkflowExecutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
