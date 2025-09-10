// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonElement
import com.lemline.common.logger.logger
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
import com.lemline.core.processor.Processor
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.SubflowInput
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
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
@ExperimentalTime
abstract class NodeInstance<T : TaskBase>(open val node: Node<T>, open val parent: NodeInstance<*>?) {

    val logger = logger()

    /**
     * Node internal initialStates
     */
    internal var state = NodeState()

    /**
     * Additional properties for this scope (for example, from a For task)
     */
    internal var variables = JsonObject(mapOf())

    /**
     * Possible children of this task
     */
    lateinit var children: List<NodeInstance<*>>

    /**
     * Root instance of the workflow.
     */
    internal val rootInstance: RootInstance by lazy {
        when (this) {
            is RootInstance -> this
            else -> parent?.rootInstance
                ?: raiseError(RUNTIME, "$this is not root, but does not have a parent")
        }
    }

    /**
     * Workflow instance that this node belongs to.
     */
    open val processor: Processor by lazy {
        rootInstance.processor
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

            else -> raiseError(CONFIGURATION, "Unknown directive: $directive")
        }
    }

    /**
     * Go to the sibling with the specified name
     */
    private fun gotoByName(name: String): NodeInstance<*> {
        val target = children.indexOfFirst { it.node.name == name }
        if (target < 0) raiseError(CONFIGURATION, "'.then' directive '$name' not found")
        childIndex = target
        return children[target]
    }

    private fun logEntering() {
        logger.debug { "Entering node ${node.name} (${node.task::class.simpleName})" }
        logger.debug { "      rawInput         = $rawInput" }
        logger.debug { "      scope            = $scope" }
        logger.debug { "      transformedInput = $transformedInput" }
    }

    private fun logLeaving() {
        logger.debug { "Leaving node ${node.name} (${node.task::class.simpleName})" }
        logger.debug { "      rawOutput         = $rawOutput" }
        logger.debug { "      scope             = $scope" }
        logger.debug { "      transformedOutput = $transformedOutput" }
    }

    private fun logSkipping() {
        logger.debug { "Skipping node ${node.name} (${node.task::class.simpleName})" }
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
     * @return true if the task should start, false otherwise
     */
    open fun shouldStart(): Boolean {
        // Test if the task should be executed
        val shouldStart = when (val `if` = node.task.`if`) {
            null -> true
            else -> evalBoolean(transformedInput, `if`, ".if", scope)
        }

        return shouldStart
    }

    /**
     * Executes the main functionality associated with the current node instance.
     *
     * This is an abstract suspend method that must be implemented by subclasses to define the specific
     * behavior or task that the node instance is responsible for. The method is executed as part of the
     * workflow lifecycle when the node's conditions are met.
     */
    abstract suspend fun run()

    /**
     * Validate a Schema
     */
    private fun validate(data: JsonElement, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        raiseError(VALIDATION, e.message, e.stackTraceToString())
    }

    /**
     * Evaluate an expression
     */
    internal fun evalString(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.isString) {
                true -> it.content
                false -> raiseError(EXPRESSION, "'.$name' expression must be a string, but is '$it'")
            }
        }

    internal fun evalBoolean(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonPrimitive && it.booleanOrNull != null) {
                true -> it.boolean
                false -> raiseError(EXPRESSION, "'.$name' expression must be a boolean, but is '$it'")
            }
        }

    internal fun evalList(data: JsonElement, expr: String, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonArray) {
                true -> it.toList()
                false -> raiseError(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
            }
        }

    private fun evalObject(data: JsonElement, expr: ExportAs, name: String, scope: JsonObject = this.scope) =
        eval(data, expr, scope).let {
            when (it is JsonObject) {
                true -> it
                false -> raiseError(EXPRESSION, "'.$name' expression must be an object, but is '$it'")
            }
        }

    internal fun eval(data: JsonElement, subFlowInput: SubflowInput?, scope: JsonObject = this.scope) =
        subFlowInput?.let { eval(data, it.additionalProperties.toJsonElement(), scope, true) } ?: data

    private fun eval(data: JsonElement, inputFrom: InputFrom?, scope: JsonObject = this.scope) =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, outputAs: OutputAs?, scope: JsonObject = this.scope) =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, exportAs: ExportAs?, scope: JsonObject = this.scope) =
        exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun eval(data: JsonElement, expr: String, scope: JsonObject = this.scope) = try {
        JQExpression.eval(data, JsonPrimitive(expr), scope, false)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(data: JsonElement, expr: JsonElement, scope: JsonObject = this.scope, force: Boolean = false) =
        try {
            JQExpression.eval(data, expr, scope, force)
        } catch (e: Exception) {
            raiseError(EXPRESSION, e.message, e.stackTraceToString())
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
    internal open fun raiseError(
        type: WorkflowErrorType,
        title: String?,
        details: String? = null,
        status: Int? = null,
    ): Nothing = raise(
        error = WorkflowError(
            errorType = type,
            title = title ?: "Unknown Error",
            details = details,
            status = status ?: type.defaultStatus,
            position = node.position,
        )
    )

    /**
     * send an exception that will be caught by the WorkflowInstance::run
     */
    protected fun raise(error: WorkflowError): Nothing = throw WorkflowException(
        raising = this,
        error = error,
    )

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
    fun getTry(error: WorkflowError): TryInstance? = when (this) {
        is TryInstance -> if (isCatching(error)) this else parent.getTry(error)
        else -> parent?.getTry(error)
    }
}

/**
 * Check if the current node has the given node as a parent.
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
