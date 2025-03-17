package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import java.time.Instant

/**
 * Base class for all task instances.
 * Task instances maintain the state of a task during execution.
 */
abstract class NodeInstance<T : TaskBase>(
    open val node: Node<T>,
    open val parent: NodeInstance<*>?
) {
    /**
     *  Possible children of this task
     */
    lateinit var children: List<NodeInstance<*>>

    /**
     * Store the current state of this task:
     * - null when the task has not started yet
     * - when started, childIndex is set to the index of the child that is currently being executed
     * - when completed, childIndex is set to children size
     */
    internal var childIndex: Int? = null

    /**
     * The time the task was started at.
     */
    internal lateinit var startedAt: Instant

    /**
     * The task raw input.
     */
    internal lateinit var rawInput: JsonNode

    /**
     * The task transformed input.
     */
    internal lateinit var transformedInput: JsonNode

    /**
     * The task raw output.
     */
    internal lateinit var rawOutput: JsonNode

    /**
     * The task transformed output.
     */
    internal lateinit var transformedOutput: JsonNode

    private val taskDescriptor: ObjectNode
        get() = JsonUtils.mapper().createObjectNode().apply {
            put("name", node.name)
            put("reference", node.reference)
            set<JsonNode>("definition", node.definition)
            set<JsonNode>("startedAt", JsonUtils.fromValue(DateTimeDescriptor.from(startedAt)))
            set<JsonNode>("input", rawInput)
            if (::rawOutput.isInitialized) set<JsonNode>("output", rawOutput)
        }

    internal open val scope: ObjectNode
        get() = JsonUtils.mapper().createObjectNode().apply {
            set<JsonNode>("task", taskDescriptor)
            if (::transformedInput.isInitialized) set<JsonNode>("input", rawInput)
            if (::transformedOutput.isInitialized) set<JsonNode>("output", rawOutput)
            // recursively merge with parent scope, without overriding existing keys
            merge(parent?.scope)
        }

    internal fun next(): NodeInstance<*>? = when (val flow = node.task.then?.get()) {
        null, FlowDirectiveEnum.CONTINUE -> `continue`()
        FlowDirectiveEnum.EXIT -> exit()
        FlowDirectiveEnum.END -> end()
        is String -> goTo(flow)
        else -> error("Unknown .then directive: $flow")
    }

    private fun exit(): NodeInstance<*>? {
        parent?.let { it.childIndex = it.children.size }
        return parent
    }

    private fun end(): NodeInstance<*>? = null

    internal open fun `continue`(): NodeInstance<*>? = parent

    private fun goTo(name: String): NodeInstance<*> {
        val target = parent?.children?.indexOfFirst { it.node.name == name }
            ?: error(".then directive `$name` can not be used on root")
        if (target == -1) error(".then directive `$name` not found")
        parent!!.childIndex = target
        return parent!!.children[target]
    }

    open fun shouldRun(rawInput: JsonNode): Boolean {
        this.rawInput = rawInput
        this.startedAt = Instant.now()

        // Validate task input if schema is provided
        node.task.input?.schema?.let { schema -> SchemaValidator.validate(rawInput, schema) }

        // Transform task input using `input.from` expression if provided
        evalTransformedInput()

        // Test If task should be executed
        return node.task.`if`
            ?.let { JQExpression.eval(transformedInput, it, scope) }
            ?.let { if (it.isBoolean) it.asBoolean() else error(".if condition must evaluate to a boolean, got `$it`") }
            ?: true
    }

    private fun evalTransformedInput() {
        this.transformedInput = JQExpression.eval(rawInput, node.task.input?.from, scope)
    }

    open suspend fun execute(): JsonNode {
        this.rawOutput = transformedInput
        return rawOutput
    }

    open suspend fun complete(): JsonNode {
        // Transform task output using output.as expression if provided
        val transformedOutput = JQExpression.eval(rawOutput, node.task.output?.`as`, scope)

        // Validate task output if schema is provided
        node.task.output?.schema?.let { schema -> SchemaValidator.validate(transformedOutput, schema) }

        // Update workflow context using export.as expression if provided
        node.task.export?.`as`?.let { exportAs ->
            JQExpression.eval(transformedOutput, exportAs, scope).let {
                // Validate exported context if schema is provided
                node.task.export.schema?.let { schema -> SchemaValidator.validate(it, schema) }
                // Set new context
                if (it is ObjectNode) setContext(it) else error(".export.as must evaluate to an object, got `$it`")
            }
        }

        // Return task output
        return transformedOutput
    }

    // setContext is defined in RootInstance.kt
    internal open fun setContext(context: ObjectNode) {
        parent?.setContext(context)
    }

    /**
     * Sets the internal state for this task instance.
     * The scope contains the state variables for the task.
     */
    open fun setState(state: NodeState) {
        state[INDEX]?.let {
            require(it.isInt) { "index must be an integer, but is $it" }
            childIndex = it.asInt()
        }
        state[RAW_INPUT]?.let {
            rawInput = it
            evalTransformedInput()
        }
        state[RAW_OUTPUT]?.let { rawOutput = it }
    }

    /**
     * Gets the internal state of this task instance.
     */
    open fun getState(): NodeState? = childIndex?.let {
        if (it < children.size) NodeState().apply {
            set(INDEX, JsonUtils.fromValue(it))
            set(RAW_INPUT, JsonUtils.fromValue(rawInput))
            if (::rawOutput.isInitialized) set(RAW_OUTPUT, JsonUtils.fromValue(rawOutput))
        } else null
    }

    // merge an ObjectNode with another, without overriding existing keys
    private infix fun ObjectNode.merge(other: ObjectNode?): ObjectNode {
        other?.fields()?.forEach { (key, value) -> if (key !in keys) set<JsonNode>(key, value) }
        return this
    }

    // Get keys of an ObjectNode
    private val ObjectNode.keys: List<String>
        get() = mutableListOf<String>().also {
            fieldNames().forEachRemaining { key -> it.add(key) }
        }

    private fun error(message: String): Nothing = throw IllegalArgumentException("Task ${node.reference}: $message")

    companion object {
        const val INDEX = "index"
        const val RAW_INPUT = "rawInput"
        const val RAW_OUTPUT = "rawOutput"
    }
}
