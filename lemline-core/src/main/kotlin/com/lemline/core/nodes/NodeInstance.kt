// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.common.withWorkflowContext
import com.lemline.core.errors.WorkflowError
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.errors.WorkflowException
import com.lemline.core.expressions.JQExpression
import com.lemline.core.expressions.scopes.Scope
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.instances.RootInstance
import com.lemline.core.instances.TryInstance
import com.lemline.core.json.LemlineJson
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

/**
 * Base class for all task instances.
 * Task instances maintain the initialStates of a task during execution.
 */
abstract class NodeInstance<T : TaskBase>(open val node: Node<T>, open val parent: NodeInstance<*>?) {
    private val logger = logger()

    private fun <T> withWorkflowContext(block: () -> T) = withWorkflowContext(
        workflowId = rootInstance.workflowDescriptor.id,
        workflowName = rootInstance.node.task.document.name,
        workflowVersion = rootInstance.node.task.document.version,
        nodePosition = node.position.toString(),
        block = block,
    )

    /**
     * Local debug function that sets the workflow context each time it's called
     */
    internal fun logDebug(e: Throwable? = null, message: () -> String) =
        withWorkflowContext { logger.debug(e, message) }

    /**
     * Local info function that sets the workflow context each time it's called
     */
    internal fun logInfo(e: Throwable? = null, message: () -> String) = withWorkflowContext { logger.info(e, message) }

    /**
     * Local warn function that sets the workflow context each time it's called
     */
    internal fun logWarn(e: Throwable? = null, message: () -> String) =
        withWorkflowContext { logger.warn(e, message) }

    /**
     * Local error function that sets the workflow context each time it's called
     */
    internal fun logError(e: Throwable? = null, message: () -> String) =
        withWorkflowContext { logger.error(e, message) }

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
                ?: onError(RUNTIME, "$this is not root, but does not have a parent")
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
    internal var startedAt: Instant?
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

    internal val transformedInput: JsonElement
        get() = _transformedInput
            ?: run {
                // Validate the raw input against the schema if one is provided
                node.task.input?.schema?.let { schema -> validate(rawInput, schema) }
                // Evaluate the input transformation expression if provided
                eval(rawInput, node.task.input?.from).also { _transformedInput = it }
            }

    /**
     * The task transformed output. (calculated)
     */
    private var _transformedOutput: JsonElement? = null

    internal val transformedOutput: JsonElement
        get() = _transformedOutput
            ?: eval(rawOutput!!, node.task.output?.`as`).also {
                _transformedOutput = it
                // Validate the transformed output against the schema if one is provided
                node.task.output?.schema?.let { schema -> validate(it, schema) }
            }


    /**
     * The task exported context. (calculated)
     */
    private var _exportAs: JsonObject? = null

    private val exportAs: JsonObject?
        get() = _exportAs
            ?: run {
                node.task.export?.let { export ->
                    evalObject(transformedOutput, export.`as`, ".export.as").also {
                        _exportAs = it
                        // Validate exported context using schema if provided
                        export.schema?.let { schema -> validate(it, schema) }
                    }
                }
            }

    /**
     * Reset the internal state of this instance
     */
    open fun reset() {
        _transformedInput = null
        _transformedOutput = null
        _exportAs = null
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
                ).toJsonObject(),
            )
            // recursively merge with parent scope
            .merge(parent?.scope)

    /**
     * Get the next node
     *
     * This implementation is for activities only, must be overridden for flows
     *
     * Note: continue should return null only if the workflow is finished
     */
    internal open fun `continue`(): NodeInstance<out TaskBase>? = then()

    /**
     * Get the next node, according to the `.then` directive.
     */
    internal open fun then(): NodeInstance<*>? = then(node.task.then)

    /**
     * Get the next node according to the provided flow directive.
     */
    internal fun then(flow: FlowDirective?): NodeInstance<*>? {
        if (flow == null) return parent?.`continue`()
        // find next
        return when (val directive = flow.get()) {
            is String -> parent?.gotoByName(directive)
            is FlowDirectiveEnum -> when (directive) {
                FlowDirectiveEnum.CONTINUE -> parent?.`continue`()
                FlowDirectiveEnum.EXIT -> parent
                FlowDirectiveEnum.END -> rootInstance
            }

            else -> onError(CONFIGURATION, "Unknown directive: $directive")
        }
    }

    /**
     * Go to the sibling with the specified name
     */
    private fun gotoByName(name: String): NodeInstance<*> {
        val target = children.indexOfFirst { it.node.name == name }
        if (target == -1) onError(CONFIGURATION, "'.then' directive '$name' not found")
        childIndex = target
        return children[target]
    }

    private fun logEntering() {
        logDebug { "Entering node ${node.name} (${node.task::class.simpleName})" }
        logDebug { "      rawInput         = $rawInput" }
        logDebug { "      scope            = $scope" }
        logDebug { "      transformedInput = $transformedInput" }
    }

    private fun logLeaving() {
        logDebug { "Leaving node ${node.name} (${node.task::class.simpleName})" }
        logDebug { "      rawOutput         = $rawOutput" }
        logDebug { "      scope             = $scope" }
        logDebug { "      transformedOutput = $transformedOutput" }
    }

    private fun logSkipping() {
        logDebug { "Skipping node ${node.name} (${node.task::class.simpleName})" }
    }

    internal fun skippingUpTo(next: NodeInstance<*>) {
        // log skipping current node
        logSkipping()
        // Set the next node's raw input to the current raw input
        next.rawOutput = rawInput
        // reset state from current to next
        resetUpTo(next)
        // log entering next node
        next.logEntering()
    }

    internal fun skippingSideTo(next: NodeInstance<*>) {
        // log skipping current node
        logSkipping()
        // Set the next node's raw input to the current raw input
        rawInput.let {
            reset()
            next.rawInput = it
        }
        // log entering next node
        next.logEntering()
    }

    internal fun goingUpTo(next: NodeInstance<*>) {
        // Update workflow context using export.as expression if provided
        exportAs?.let { rootInstance.context = it }
        // log leaving current node
        logLeaving()
        // Set the next node's raw output to the transformed output
        next.rawOutput = transformedOutput
        // reset state from current to next
        resetUpTo(next)
    }

    internal fun goingSideTo(next: NodeInstance<*>) {
        // Update workflow context using export.as expression if provided
        exportAs?.let { rootInstance.context = it }
        // log leaving current node
        logLeaving()
        // Set the next node's raw input to the transformed output (can be self)
        transformedOutput.let {
            reset()
            next.rawInput = it
        }
        // log entering next node
        next.logEntering()
    }

    internal fun goingDownTo(next: NodeInstance<*>) {
        // Set the next node's raw input to the transformed output
        next.rawInput = transformedInput
        // log entering next node
        next.logEntering()
    }

    /**
     * Check if the task should start based on the `if` condition.
     *
     * This method is called before executing the task to determine if it should be run.
     * It evaluates the `if` condition against the transformed input and returns true if the task should start,
     * or false if it should be skipped.
     *
     * The `if` condition is evaluated using the transformed input, which is set during the `onStart()` method.
     *
     * @return true if the task should start, false otherwise
     */
    open fun shouldStart(): Boolean {
        // Test if the task should be executed
        val shouldStart = node.task.`if`
            ?.let { evalBoolean(transformedInput, it, ".if") }
            ?: true

        return shouldStart
    }

    open suspend fun run() {
        // this method should be overridden by subclasses to implement the activity logic
    }

    /**
     * Validate a Schema
     */
    private fun validate(data: JsonElement, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        onError(VALIDATION, e.message, e.stackTraceToString())
    }

    /**
     * Evaluate an expression
     */
    internal fun evalString(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.isString) {
                true -> it.content
                false -> onError(EXPRESSION, "'.$name' expression must be a string, but is '$it'")
            }
        }

    internal fun evalBoolean(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.booleanOrNull != null) {
                true -> it.boolean
                false -> onError(EXPRESSION, "'.$name' expression must be a boolean, but is '$it'")
            }
        }

    internal fun evalList(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonArray) {
                true -> it.toList()
                false -> onError(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
            }
        }

    private fun evalObject(data: JsonElement, expr: ExportAs, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonObject) {
                true -> it
                false -> onError(EXPRESSION, "'.$name' expression must be an object, but is '$it'")
            }
        }

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject = this.scope) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject = this.scope) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, exportAs: ExportAs?, scope: JsonObject = this.scope) =
        exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject = this.scope) = try {
        JQExpression.eval(data, JsonPrimitive(expr), scope, false)
    } catch (e: Exception) {
        onError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(data: JsonElement, expr: JsonElement, scope: JsonObject = this.scope, force: Boolean = false) =
        try {
            JQExpression.eval(data, expr, scope, force)
        } catch (e: Exception) {
            onError(EXPRESSION, e.message, e.stackTraceToString())
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
    internal fun onError(
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
            error = error,
        )
    }

    internal fun resetUpTo(node: NodeInstance<*>) {
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

/**
 * Check if the current node has the given node as parent.
 */
internal fun NodeInstance<*>?.isGoingUp(node: NodeInstance<*>?): Boolean = when {
    this == null -> false
    node == null -> true
    else -> parent == node || parent?.isGoingUp(node) == true
}

/**
 * Check if the current node is going down to the given node.
 */
internal fun NodeInstance<*>?.isGoingDown(node: NodeInstance<*>?): Boolean = node.isGoingUp(this)
