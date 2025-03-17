package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.Scope
import com.lemline.swruntime.expressions.scopes.TaskDescriptor
import com.lemline.swruntime.schemas.SchemaValidator
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
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
    internal lateinit var startedAt: DateTimeDescriptor

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

    private val taskDescriptor
        get() = TaskDescriptor(
            name = node.name,
            reference = node.reference,
            definition = node.definition,
            startedAt = startedAt,
            input = rawInput,
            output = if (::rawOutput.isInitialized) rawOutput else null
        )

    /**
     * This scope is used during expression evaluation
     */
    internal open val scope: ObjectNode
        get() = Scope().apply {
            setTask(taskDescriptor)
            if (::transformedInput.isInitialized) setInput(rawInput)
            if (::transformedOutput.isInitialized) setOutput(rawOutput)
        }.toJson()
            // recursively merge with parent scope, without overriding existing keys
            .merge(parent?.scope)

    /**
     * Get the next node instance
     */
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

    /**
     * Get the next node instance, for the `continue` flow directive
     * (This function should be overridden for flow nodes)
     */
    internal open fun `continue`(): NodeInstance<*>? = parent

    private fun goTo(name: String): NodeInstance<*> {
        val target = parent?.children?.indexOfFirst { it.node.name == name }
            ?: error(".then directive `$name` can not be used on root")
        if (target == -1) error(".then directive `$name` not found")
        parent!!.childIndex = target
        return parent!!.children[target]
    }

    open fun shouldRun(rawInput: JsonNode): Boolean {
        // if we already started this node, do not ask again
        // (this is important as we do not want to override rawInput, startedAt and transformedInput)
        if (childIndex != null) return true

        // Validate task input if schema is provided
        node.task.input?.schema?.let { schema -> SchemaValidator.validate(rawInput, schema) }

        // Transform task input using `input.from` expression if provided
        val transformedInput = evalTransformedInput()

        // Test If task should be executed
        val shouldRun = node.task.`if`
            ?.let { JQExpression.eval(transformedInput, it, scope) }
            ?.let { if (it.isBoolean) it.asBoolean() else error("result of .if condition must be a boolean, but is `$it`") }
            ?: true

        // if yes, initialize the task state
        if (shouldRun) {
            this.rawInput = rawInput
            this.startedAt = DateTimeDescriptor.from(Instant.now())
            this.transformedInput = transformedInput
        }

        return shouldRun
    }

    private fun evalTransformedInput() = JQExpression.eval(rawInput, node.task.input?.from, scope)

    open suspend fun execute(): JsonNode {
        this.rawOutput = transformedInput
        return rawOutput
    }

    open suspend fun complete() {
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
                if (it is ObjectNode) setContext(it) else error("result of .export.as must be an object, but is `$it`")
            }
        }

        // set task output
        this.transformedOutput = transformedOutput
    }

    // recursively process setContext up to RootInstance
    internal open fun setContext(context: ObjectNode) {
        parent?.setContext(context)
    }

    /**
     * Set the internal state of this task instance.
     */
    open fun setState(state: NodeState) {
        state.getIndex()?.let { childIndex = it }
        state.getRawInput()?.let {
            this.rawInput = it
            this.transformedInput = evalTransformedInput()
        }
        state.getRawOutput()?.let { this.rawOutput = it }
    }

    /**
     * Gets the internal state of this task instance.
     */
    open fun getState(): NodeState? = childIndex?.let {
        if (it < children.size) NodeState().apply {
            setIndex(it)
            setRawInput(rawInput)
            setStartedAt(startedAt)
            if (::rawOutput.isInitialized) setRawOutput(rawOutput)
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
}
