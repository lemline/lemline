package com.lemline.core.nodes

import com.lemline.common.*
import com.lemline.core.errors.WorkflowError
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.*
import com.lemline.core.errors.WorkflowException
import com.lemline.core.expressions.JQExpression
import com.lemline.core.expressions.scopes.Scope
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.flows.RootInstance
import com.lemline.core.nodes.flows.TryInstance
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.*

/**
 * Base class for all task instances.
 * Task instances maintain the initialStates of a task during execution.
 */
abstract class NodeInstance<T : TaskBase>(
    open val node: Node<T>,
    open val parent: NodeInstance<*>?
) {
    private val logger = logger()

    private fun <T> withWorkflowContext(
        block: () -> T
    ) = withWorkflowContext(
        workflowId = rootInstance.workflowDescriptor.id,
        workflowName = rootInstance.node.task.document.name,
        workflowVersion = rootInstance.node.task.document.version,
        nodePosition = node.position.toString(),
        block = block
    )

    /**
     * Local debug function that sets the workflow context each time it's called
     */
    internal fun debug(e: Throwable? = null, message: () -> String) = withWorkflowContext { logger.debug(e, message) }

    /**
     * Local info function that sets the workflow context each time it's called
     */
    internal fun info(e: Throwable? = null, message: () -> String) = withWorkflowContext { logger.info(e, message) }

    /**
     * Local error function that sets the workflow context each time it's called
     */
    internal fun error(e: Throwable? = null, message: () -> String) = withWorkflowContext { logger.error(e, message) }

    /**
     * Node internal initialStates
     */
    internal var state = NodeState()

    /**
     * Possible children of this task
     */
    lateinit var children: List<NodeInstance<*>>

    /**
     * Root initialPosition
     */
    internal val rootInstance: RootInstance by lazy {
        when (this) {
            is RootInstance -> this
            else -> parent?.rootInstance
                ?: error(RUNTIME, "$this is not root, but does not have a parent")
        }
    }

    /**
     * Index of the current child being processed
     */
    internal var childIndex: Int
        get() = state.childIndex
        set(value) {
            state.childIndex = value
        }

    /**
     * Additional properties for this scope (for example from For task)
     */
    internal var variables: JsonObject
        get() = state.variables
        set(value) {
            state.variables = value
        }

    /**
     * The time the task was started at.
     */
    private var startedAt: Instant?
        get() = state.startedAt
        set(value) {
            state.startedAt = value
        }

    /**
     * The task raw input.
     */
    internal var rawInput: JsonElement
        get() = state.rawInput!!
        set(value) {
            state.rawInput = value
        }

    /**
     * The task raw output.
     */
    internal var rawOutput: JsonElement?
        get() = state.rawOutput
        set(value) {
            state.rawOutput = value
        }

    /**
     * The task transformed input. (calculated)
     */
    private var _transformedInput: JsonElement? = null

    internal var transformedInput: JsonElement
        get() = _transformedInput ?: eval(rawInput, node.task.input?.from).also { _transformedInput = it }
        set(value) {
            _transformedInput = value
        }

    /**
     * The task transformed output. (calculated)
     */
    private var _transformedOutput: JsonElement? = null

    internal val transformedOutput: JsonElement
        get() = _transformedOutput ?: eval(rawOutput!!, node.task.output?.`as`).also { _transformedOutput = it }


    /**
     * Reset the internal state of this instance
     */
    open fun reset() {
        _transformedInput = null
        _transformedOutput = null
        state = NodeState()
    }

    /**
     * Recalculate the task descriptor
     */
    private val taskDescriptor
        get() = TaskDescriptor(
            name = node.name,
            reference = node.reference,
            definition = node.definition,
            startedAt = startedAt?.let { LemlineJson.encodeToElement(DateTimeDescriptor.from(it.toJavaInstant())) },
            input = rawInput,
            output = rawOutput,
        )

    /**
     * Scope used during expression evaluation
     */
    internal open val scope: JsonObject
        get() = variables
            // merge current scope
            .merge(
                Scope(
                    task = taskDescriptor,
                    input = rawInput,
                    output = rawOutput,
                ).toJsonObject()
            )
            // recursively merge with parent scope
            .merge(parent?.scope)

    /**
     * Get the next node initialPosition after completion
     */
    internal fun then(): NodeInstance<*>? = then(node.task.then?.get())

    /**
     * Get the next node initialPosition based on the provided flow directive.
     *
     * @param flow The flow directive which can be one of the following:
     * - `null` or `FlowDirectiveEnum.CONTINUE`: Continue to the next sibling.
     * - `FlowDirectiveEnum.EXIT`: Exit the current flow and continue with the parent flow.
     * - `FlowDirectiveEnum.END`: End the workflow.
     * - `String`: Go to the sibling with the specified name.
     * - Any other value will result in an error.
     *
     * @return The next node initialPosition or `null` if we reach the end of the workflow
     */
    internal fun then(flow: Any?): NodeInstance<*>? {
        // calculate transformedOutput
        // validate schema
        // export context
        // set parent raw output
        complete()
        // find next
        return when (flow) {
            null, FlowDirectiveEnum.CONTINUE -> parent?.`continue`()
            FlowDirectiveEnum.EXIT -> parent?.then()
            FlowDirectiveEnum.END -> parent?.end()
            is String -> parent?.goTo(flow)
            else -> error(CONFIGURATION, "Unknown '.then' directive: $flow")
        }
    }

    /**
     * Get the next node initialPosition, for the `continue` flow directive
     * This implementation is for activities only, must be overridden for flows
     * Note: continue should return null only if the workflow is finished
     */
    internal open fun `continue`(): NodeInstance<out TaskBase>? = then()

    /**
     * Go to the sibling with the specified name
     */
    private fun goTo(name: String): NodeInstance<*> {
        val target = children.indexOfFirst { it.node.name == name }
        if (target == -1) error(CONFIGURATION, "'.then' directive '$name' not found")
        childIndex = target
        return children[target].also { it.rawInput = rawOutput!! }
    }

    /**
     * End the workflow right away
     */
    private fun end(): RootInstance? {
        // complete current node
        complete()

        // recursively up to Root
        return when (this) {
            is RootInstance -> null
            else -> parent!!.end()
        }
    }

    /**
     * Determines if the task should be entered based on its condition.
     *
     * This method:
     * 1. Initializes the node by calling start() to set timestamps and transform input
     * 2. Evaluates the node's conditional expression (if present)
     * 3. Resets the node state if the condition evaluates to false
     *
     * The algorithm handles conditional execution based on the task's `if` property:
     * - If no condition is specified, the node is always executed (returns true)
     * - If a condition is specified, it's evaluated against the transformed input
     * - If the condition evaluates to false, the node state is reset
     *
     * Edge cases:
     * - If the condition expression is invalid or doesn't evaluate to a boolean, an error is thrown
     * - If the condition evaluates to false, the node is reset to its initial state
     *
     * @return `true` if the task should be entered, `false` otherwise.
     */
    open fun shouldStart(): Boolean {
        // set the start time, validate and transform the input, used for the calculation
        start()
        // Test if the task should be executed
        val shouldStart = node.task.`if`
            ?.let { evalBoolean(transformedInput, it, ".if") }
            ?: true

        if (!shouldStart) reset()

        return shouldStart
    }

    /**
     * Initializes node execution by setting timestamps, validating input, and transforming input data.
     *
     * This method is called when a node is about to be executed and performs several key functions:
     * 1. Records the start time of the node execution
     * 2. Logs entry into the node with input data for debugging
     * 3. Validates the raw input against a schema if one is provided
     * 4. Transforms the input using expressions if configured
     *
     * The input transformation process:
     * - If no input transformation is configured, rawInput is used as-is
     * - If input.from is specified, the expression is evaluated against rawInput
     * - The transformed input is available via the transformedInput property
     *
     * Validation behavior:
     * - If input.schema is provided, the rawInput is validated against it
     * - If validation fails, a VALIDATION error is thrown and execution stops
     *
     * Prerequisites:
     * - The rawInput property must be set before calling this method
     *
     * @return The transformed input as a JSON element, also available via the transformedInput property
     * @throws WorkflowException with VALIDATION error type if input validation fails
     */
    internal fun start(): JsonElement {
        startedAt = Clock.System.now()

        debug { "Entering node ${node.name} (${node.task::class.simpleName})" }
        debug { "      rawInput         = $rawInput" }
        debug { "      scope            = $scope" }

        // Validate rawInput using schema if provided
        node.task.input?.schema?.let { schema -> validate(rawInput, schema) }

        debug { "      transformedInput = $transformedInput" }

        return transformedInput
    }

    open suspend fun execute() {
        this.rawOutput = transformedInput
    }

    /**
     * Finalizes node execution by validating output, updating context, and resetting state.
     *
     * This method is called when a node has finished execution and performs several key functions:
     * 1. Logs completion of the node with output data for debugging
     * 2. Validates the transformed output against a schema if one is provided
     * 3. Updates the workflow context using export expressions if configured
     * 4. Propagates the output to the parent node
     * 5. Resets the node state to prepare for potential reuse
     *
     * The output processing workflow:
     * 1. Output validation: If output.schema is provided, the transformedOutput is validated
     * 2. Context update: If export.as is specified, the expression is evaluated and validated
     * 3. Parent update: The parent node's rawOutput is set to this node's transformedOutput
     * 4. State reset: The node state is reset to its initial state
     *
     * Validation behavior:
     * - If output.schema is provided, the transformedOutput is validated against it
     * - If export.schema is provided, the exported context is validated against it
     * - If validation fails, a VALIDATION error is thrown and execution stops
     *
     * Context update behavior:
     * - The export.as expression must evaluate to a JSON object
     * - The resulting object becomes the new workflow context
     * - The previous context is completely replaced (not merged)
     *
     * @throws WorkflowException with VALIDATION error type if output validation fails
     * @throws WorkflowException with EXPRESSION error type if export.as doesn't evaluate to an object
     */
    private fun complete() {
        debug { "Leaving node ${node.name} (${node.task::class.simpleName})" }
        debug { "      rawOutput        = $rawOutput" }
        debug { "      scope            = $scope" }
        debug { "      transformedOutput = $transformedOutput" }

        // Validate transformedOutput using schema if provided
        node.task.output?.schema?.let { schema -> validate(transformedOutput, schema) }

        // Update workflow context using export.as expression if provided
        node.task.export?.let { export ->
            val exportAs = evalObject(transformedOutput, export.`as`, ".export.as")
            // Validate exported context using schema if provided
            export.schema?.let { schema -> validate(exportAs, schema) }
            // Set new context
            rootInstance.context = exportAs
        }

        // set current parent raw output
        parent?.rawOutput = transformedOutput

        // reset the node instance
        reset()
    }

    /**
     * Validate a Schema
     */
    private fun validate(data: JsonElement, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        error(VALIDATION, e.message, e.stackTraceToString())
    }

    /**
     * Evaluate an expression
     */
    internal fun evalString(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.isString) {
                true -> it.content
                false -> error(EXPRESSION, "'.$name' expression must be a string, but is '$it'")
            }
        }

    internal fun evalBoolean(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.booleanOrNull != null) {
                true -> it.boolean
                false -> error(EXPRESSION, "'.$name' expression must be a boolean, but is '$it'")
            }
        }

    internal fun evalList(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonArray) {
                true -> it.toList()
                false -> error(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
            }
        }

    private fun evalObject(data: JsonElement, expr: ExportAs, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonObject) {
                true -> it
                false -> error(EXPRESSION, "'.$name' expression must be an object, but is '$it'")
            }
        }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject = this.scope) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject = this.scope) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, exportAs: ExportAs?, scope: JsonObject = this.scope) =
        exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject = this.scope) = try {
        JQExpression.eval(data, expr, scope)
    } catch (e: Exception) {
        error(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(data: JsonElement, expr: JsonElement, scope: JsonObject = this.scope, force: Boolean = false) =
        try {
            JQExpression.eval(data, expr, scope, force)
        } catch (e: Exception) {
            error(EXPRESSION, e.message, e.stackTraceToString())
        }

    /**
     * Merge a JsonObject with another, without overriding existing keys
     */
    private fun JsonObject.merge(other: JsonObject?): JsonObject {
        val mergedMap = buildMap {
            other?.forEach { put(it.key, it.value) }
            this@merge.forEach { put(it.key, it.value) }
        }
        return JsonObject(mergedMap)
    }

    /**
     * Create an error and raise it
     */
    internal fun error(
        type: WorkflowErrorType,
        title: String?,
        details: String? = null,
        status: Int = type.defaultStatus,
    ): Nothing {
        val error = WorkflowError(
            errorType = type,
            title = title ?: "Unknown Error",
            details = details,
            status = status,
            position = node.position
        )

        raise(error)
    }

    /**
     * Raise an error and propagate it to the parent
     */
    protected fun raise(error: WorkflowError): Nothing {
        // get catching try if any reset initialStates up to it
        val catching: TryInstance? = getTry(error)?.also { resetUpTo(it) }

        // send an exception that will be caught by the WorkflowInstance::run
        throw WorkflowException(
            raising = this,
            catching = catching,
            error = error
        )
    }

    private fun resetUpTo(node: TryInstance) {
        reset()
        parent?.let {
            when (it) {
                node -> Unit
                else -> it.resetUpTo(node)
            }
        }
    }

    /**
     * Get the try parent (if any)
     */
    private fun getTry(error: WorkflowError): TryInstance? = when (this) {
        is TryInstance -> if (isCatching(error)) this else parent.getTry(error)
        else -> parent?.getTry(error)
    }
}
