package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.expressions.scopes.Scope
import com.lemline.swruntime.expressions.scopes.TaskDescriptor
import com.lemline.swruntime.schemas.SchemaValidator
import com.lemline.swruntime.tasks.flows.RootInstance
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
    open val node: NodeTask<T>,
    open val parent: NodeInstance<*>?
) {
    /**
     *  Possible children of this task
     */
    lateinit var children: List<NodeInstance<*>>

    /**
     * Store the current state of this task:
     * - -1 when the task has not started yet
     * - when started, childIndex is set to the index of the child that is currently being executed
     * - when completed, childIndex is set to children size
     */
    internal var childIndex: Int = -1

    /**
     * The time the task was started at.
     */
    internal var startedAt: DateTimeDescriptor? = null

    /**
     * The task raw input.
     */
    internal var rawInput: JsonNode? = null

    /**
     * The task transformed input.
     */
    internal var transformedInput: JsonNode? = null

    /**
     * The task raw output.
     */
    internal var rawOutput: JsonNode? = null

    /**
     * The task transformed output.
     */
    internal var transformedOutput: JsonNode? = null

    /**
     * Additional properties for this scope (added by a child Set task)
     */
    internal var customScope: ObjectNode = JsonUtils.`object`()

    private val taskDescriptor
        get() = TaskDescriptor(
            name = node.name,
            reference = node.reference,
            definition = node.definition,
            startedAt = startedAt,
            input = rawInput!!,
            output = rawOutput,
        )

    /**
     * This scope is used during expression evaluation
     */
    internal open val scope: ObjectNode
        get() = customScope
            // merge custom scope with current
            .merge(
                Scope().apply {
                    setTask(taskDescriptor)
                    rawInput?.let { setInput(it) }
                    rawOutput?.let { setOutput(it) }
                }.toJson()
            )
            // recursively merge with parent scope
            .merge(parent?.scope)

    /**
     * Get the next node instance after completion
     */
    internal fun then(): NodeInstance<*>? = then(node.task.then?.get())

    /**
     * Get the next node instance based on the provided flow directive.
     *
     * @param flow The flow directive which can be one of the following:
     * - `null` or `FlowDirectiveEnum.CONTINUE`: Continue to the next sibling.
     * - `FlowDirectiveEnum.EXIT`: Exit the current flow and continue with the parent flow.
     * - `FlowDirectiveEnum.END`: End the workflow.
     * - `String`: Go to the sibling with the specified name.
     * - Any other value will result in an error.
     *
     * @return The next node instance or `null` if there is no next node.
     */
    internal fun then(flow: Any?): NodeInstance<*>? {
        // calculate transformedOutput
        // validate schema
        // export context
        // set parent raw output
        onLeave()
        // reset the task instance
        reset()
        // find next
        return when (flow) {
            null, FlowDirectiveEnum.CONTINUE -> parent?.`continue`()
            FlowDirectiveEnum.EXIT -> parent?.then()
            FlowDirectiveEnum.END -> parent?.end()
            is String -> parent?.goTo(flow)
            else -> error("Unknown '.then' directive: $flow")
        }
    }

    /**
     * Get the next node instance, for the `continue` flow directive
     * This implementation is for activities only, must be overridden for flows
     */
    internal open fun `continue`(): NodeInstance<*>? = parent?.`continue`()

    /**
     * Go to the sibling with a specific name
     */
    private fun goTo(name: String): NodeInstance<*> {
        val target = children.indexOfFirst { it.node.name == name }
        if (target == -1) error("'.then' directive '$name' not found")
        childIndex = target
        return children[target].also { it.rawInput = rawOutput }
    }

    /**
     * End the workflow right away
     */
    private fun end(): RootInstance? = when (this) {
        is RootInstance -> null
        else -> {
            onLeave()
            reset()
            parent!!.end()
        }
    }

    /**
     * Called when the task is entered. RawInput should be set before.
     *
     * This method:
     * - sets the start time,
     * - validates the task input if a schema is provided,
     * - transforms the task input using the `input.from` expression if provided.
     *
     * @return The transformed input as a JSON node.
     */
    internal fun onEnter(): JsonNode {
        this.startedAt = DateTimeDescriptor.from(Instant.now())
        // Validate task input if schema is provided
        node.task.input?.schema?.let { schema -> SchemaValidator.validate(rawInput!!, schema) }
        // Transform task input using 'input.from' expression if provided
        this.transformedInput = evalTransformedInput()
        // Set default behavior
        this.rawOutput = transformedInput

        println("Enter task = ${node.name} (${node.task::class.simpleName})")
        println("      rawInput         = $rawInput")
        println("      transformedInput = $transformedInput")

        return transformedInput!!
    }

    /**
     * Determines if the task should be entered.
     *
     * @return `true` if the task should be entered, `false` otherwise.
     */
    open fun shouldEnter(): Boolean {
        // set start time, validate and transform the input, used for the calculation
        onEnter()
        // Test If task should be executed
        val shouldRun = node.task.`if`
            ?.let { JQExpression.eval(transformedInput!!, it, scope) }
            ?.let { if (it.isBoolean) it.asBoolean() else error("result of '.if' condition must be a boolean, but is '$it'") }
            ?: true

        if (!shouldRun) reset()

        return shouldRun
    }

    private fun evalTransformedInput() = JQExpression.eval(rawInput!!, node.task.input?.from, scope)

    open suspend fun execute() {
        this.rawOutput = transformedInput
    }

    open fun onLeave(): JsonNode {
        // Transform task output using output.as expression if provided
        transformedOutput = JQExpression.eval(rawOutput!!, node.task.output?.`as`, scope)

        println("Leave task = ${node.name} (${node.task::class.simpleName})")
        println("      rawOutput         = $rawOutput")
        println("      transformedOutput = $transformedOutput")

        // Validate task output if schema is provided
        node.task.output?.schema?.let { schema -> SchemaValidator.validate(transformedOutput!!, schema) }

        // Update workflow context using export.as expression if provided
        node.task.export?.`as`?.let { exportAs ->
            JQExpression.eval(transformedOutput!!, exportAs, scope).let {
                // Validate exported context if schema is provided
                node.task.export.schema?.let { schema -> SchemaValidator.validate(it, schema) }
                // Set new context
                if (it is ObjectNode) setContext(it) else error("result of '.export.as' must be an object, but is '$it'")
            }
        }

        // set current flow raw output
        parent?.rawOutput = transformedOutput

        return transformedOutput!!
    }

    open fun reset() {
        childIndex = -1
        customScope = JsonUtils.`object`()
        startedAt = null
        rawInput = null
        transformedInput = null
        rawOutput = null
        transformedOutput = null
    }

    // recursively process setContext up to RootInstance
    internal open fun setContext(context: ObjectNode) {
        parent?.setContext(context)
    }

    /**
     * Set the internal state of this task instance.
     */
    open fun setState(state: NodeState) {
        childIndex = state.getIndex()
        customScope = state.getVariables()
        state.getRawInput()?.let {
            this.rawInput = it
            if (this !is RootInstance && childIndex == -1) this.transformedInput = evalTransformedInput()
        }
        state.getRawOutput()?.let { this.rawOutput = it }
    }

    /**
     * Gets the internal state of this task instance.
     */
    open fun getState(): NodeState? = childIndex.let { index ->
        if (index < children.size) NodeState().apply {
            setIndex(index)
            if (!customScope.isEmpty) setVariables(customScope)
            rawInput?.let { setRawInput(it) }
            startedAt?.let { setStartedAt(it) }
            rawOutput?.let { setRawOutput(it) }
        } else null
    }

    // merge an ObjectNode with another, without overriding existing keys
    internal infix fun ObjectNode.merge(other: ObjectNode?): ObjectNode {
        other?.fields()?.forEach { (key, value) -> if (key !in keys) set<JsonNode>(key, value) }
        return this
    }

    // Get keys of an ObjectNode
    private val ObjectNode.keys: List<String>
        get() = mutableListOf<String>().also {
            fieldNames().forEachRemaining { key -> it.add(key) }
        }

    protected fun error(message: String): Nothing = throw IllegalArgumentException("Task ${node.reference}: $message")
}
