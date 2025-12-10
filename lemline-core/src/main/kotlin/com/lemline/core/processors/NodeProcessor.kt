// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonElement
import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.expressions.JQExpression
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.withRawOutput
import com.lemline.core.processors.scope.withTask
import com.lemline.core.processors.scope.withTransformedInput
import com.lemline.core.processors.scope.withTransformedOutput
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.NodeStack
import com.lemline.core.states.NodeState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ForkBranchCompleted
import com.lemline.core.states.WorkflowEvent.ListenForEachCompleted
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.states.WorkflowEvent.WorkflowCompleted
import com.lemline.core.workflows.toKotlin
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.ListenTask
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
abstract class NodeProcessor<T : TaskBase, S : NodeState>(
    val node: Node<T>
) {
    val logger = logger()

    // ========================================
    // Task-Specific Actions
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

    open val isAsync: Boolean = false

    /**
     * return started event for activities
     */
    open fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent = error("startedEvent should not be called for $node")

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
    suspend fun enterFromParent(
        nodeStack: NodeStack,
        rawInput: JsonElement,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        logger.debug { "Entering Down  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput)" }

        // Validate input against schema (throws ValidationException)
        validateInput(rawInput)

        // Create execution context
        val startedAt = Clock.System.now()

        // Get current scope from states
        var scope = nodeStack.stateScope

        // create a mutable local task context
        scope = scope.withTask(node, startedAt, rawInput)

        // if this node is conditional, check if it should be executed, if not return to parent
        if (!checkIf(rawInput, scope)) {
            // Emit task completed (there is not skipped type)
            lifecycleHook.onTaskCompleted(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                nodePosition = node.position,
                output = rawInput,
                completedAt = Clock.System.now(),
            )

            return getNextEvent(
                nodeStack = nodeStack,
                nextNode = node.parent,
                nextInput = rawInput,
                nextDirective = null, // Continue to next sibling
            )
        }

        // Apply input transformation (throws ExpressionException)
        val transformedInput = transformInput(rawInput, scope)

        // Update context with transformed input
        scope = scope.withTransformedInput(transformedInput)

        // state creation
        val state = stateEnterFromParent(transformedInput, scope)

        // push the state to the stack
        val updatedStack = nodeStack.push(node.position to state)

        // get the next node and an updated state for the current node
        return continueTo(state, transformedInput, scope, updatedStack, workflowInfo, lifecycleHook)
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
        nodeStack: NodeStack,
        output: JsonElement,
        flowDirective: FlowDirective?,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        logger.debug {
            "ReEntering Up  node=${node.position} - ${node.task::class.simpleName}(input=$output${
                flowDirective?.get()?.let { ", directive=$it" } ?: ""
            }), state=${nodeStack.lastState}"
        }
        @Suppress("UNCHECKED_CAST")
        val state = nodeStack.lastState as S
        val scope = nodeStack.stateScope.withTask(node, state.startedAt)

        val updatedState = stateEnterFromChild(state, output, scope, flowDirective.nodeName)

        return when (val directive = flowDirective?.get()) {
            is FlowDirectiveEnum -> when (directive) {
                // END: Workflow complete - recursive unwinding
                FlowDirectiveEnum.END -> continueToEnd(
                    output = output,
                    nodeStack = nodeStack,
                    workflowInfo = workflowInfo,
                    lifecycleHook = lifecycleHook
                )
                // EXIT: exit current node
                FlowDirectiveEnum.EXIT -> continueToParent(
                    state = updatedState,
                    transformedInput = output,
                    currentFlowDirective = getFlowDirective(),
                    scope = scope,
                    nodeStack = nodeStack,
                    workflowInfo = workflowInfo,
                    lifecycleHook = lifecycleHook,
                )
                // CONTINUE: continue
                FlowDirectiveEnum.CONTINUE -> continueTo(
                    state = updatedState,
                    transformedInput = output,
                    scope = scope,
                    nodeStack = nodeStack,
                    workflowInfo = workflowInfo,
                    lifecycleHook = lifecycleHook
                )
            }

            // Goto named sibling or null
            is String, null -> continueTo(
                state = updatedState,
                transformedInput = output,
                scope = scope,
                nodeStack = nodeStack,
                workflowInfo,
                lifecycleHook
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
        scope: Scope,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        // Pure navigation - determine where to go next
        val (nextNode, nextDirective) = getNextNode(state, transformedInput, scope)

        // check if we should return to parent
        return when (nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(
                state = state,
                transformedInput = transformedInput,
                currentFlowDirective = nextDirective,
                scope = scope,
                nodeStack = nodeStack,
                workflowInfo = workflowInfo,
                lifecycleHook = lifecycleHook,
            )

            // control flows that are not completed (do, for, ...), going to a child
            false -> continueToChild(
                childNode = nextNode,
                childRawInput = transformedInput,
                updatedState = state,
                nodeStack = nodeStack
            )
        }
    }

    private fun continueToChild(
        childNode: Node<*>?,
        childRawInput: JsonElement,
        updatedState: NodeState,
        nodeStack: NodeStack
    ) = getNextEvent(
        nodeStack = nodeStack.pop().push(node.position to updatedState),
        nextNode = childNode,
        nextInput = childRawInput,
        nextDirective = null,
    )

    // ========================================
    // EXIT
    // ========================================
    private suspend fun continueToParent(
        state: S,
        transformedInput: JsonElement,
        currentFlowDirective: FlowDirective?,
        scope: Scope,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {

        // it the task is async, return started event
        if (isAsync) return startedEvent(nodeStack, transformedInput, scope)

        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = execute(transformedInput, scope, state)

        // Complete the task with the raw output
        return completeTask(rawOutput, currentFlowDirective, scope, nodeStack, workflowInfo, lifecycleHook)
    }

    /**
     * Complete a task with the given raw output.
     * Applies output transformation, validation, and context export.
     * This is separated from continueToParent to allow the orchestrator to
     * complete tasks that were executed externally (e.g., child workflows).
     */
    internal suspend fun completeTask(
        rawOutput: JsonElement,
        currentFlowDirective: FlowDirective?,
        currentScope: Scope,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        // Update scope with raw output
        var scope = currentScope.withRawOutput(rawOutput)

        // Apply output transformation (throws ExpressionException)
        val transformedOutput = transformOutput(rawOutput, scope)

        // Validate output (throws ValidationException)
        validateOutput(transformedOutput)

        // Update scope with transformed output
        scope = scope.withTransformedOutput(transformedOutput)

        // Export to context if export.as is defined (throws ExpressionException or ValidationException)
        val exportedContext = exportToContext(transformedOutput, scope)

        // Emit task completed events
        if (node.position != NodePosition.root) {
            lifecycleHook.onTaskCompleted(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                nodePosition = node.position,
                output = transformedOutput,
                completedAt = Clock.System.now(),
            )
        }

        // Build the updated state stack: pop the current node's state (but never root)
        // State was pushed in enterFromParent, so it's always on the stack
        var updatedStack = if (node.position == NodePosition.root) nodeStack else nodeStack.pop()
        if (exportedContext != null) {
            updatedStack = updatedStack.setContext(exportedContext)
        }

        return getNextEvent(
            nodeStack = updatedStack,
            nextNode = node.parent,
            nextInput = transformedOutput,
            nextDirective = currentFlowDirective,
        )
    }

    // ========================================
    // END
    // ========================================

    private suspend fun continueToEnd(
        output: JsonElement,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook
    ): WorkflowEvent {

        // Emit task completed events
        if (node.position != NodePosition.root) {
            lifecycleHook.onTaskCompleted(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                nodePosition = node.position,
                output = output,
                completedAt = Clock.System.now(),
            )
        }

        return getNextEvent(
            // Pop the current node's state (but never root) - state was pushed in enterFromParent
            nodeStack = if (node.position == NodePosition.root) nodeStack else nodeStack.pop(),
            nextNode = node.parent,
            nextInput = output,
            nextDirective = FlowDirective().apply { setFlowDirectiveEnum(FlowDirectiveEnum.END) } // Pass END up the chain
        )
    }

    // ========================================
    // Next Event
    // ========================================

    private fun getNextEvent(
        nodeStack: NodeStack,
        nextNode: Node<*>?,
        nextInput: JsonElement,
        nextDirective: FlowDirective? = null,
    ): WorkflowEvent {
        // Workflow completed
        if (nextNode == null) {
            logger.debug { "Workflow completed with output: $nextInput" }

            return WorkflowCompleted(
                output = nextInput,
                completedAt = Clock.System.now(),
                nodeStack = nodeStack,
            )
        }

        // Are we now completing a fork branch?
        forkBranchCompleted(nodeStack, nextNode, nextInput)?.let { return it }

        // Are we now completing a listen foreach iteration?
        listenForEachCompleted(nodeStack, nextNode, nextInput)?.let { return it }

        // Next Task
        return TaskScheduled(
            nodeStack = nodeStack,
            nodePosition = nextNode.position,
            rawInput = nextInput,
            flowDirective = nextDirective?.toKotlin(),
        )

    }

    /**
     * Determines if a fork branch has completed successfully based on the current node, its stack, and directive,
     * and returns the corresponding `ForkBranchCompleted` event if applicable.
     */
    private fun forkBranchCompleted(
        nodeStack: NodeStack,
        nextNode: Node<*>,
        nextInput: JsonElement,
    ): ForkBranchCompleted? {
        val nextState = nodeStack[nextNode.position]

        // if nextNode is not a fork task, or if we are entering for the first time
        if (nextNode.task !is ForkTask || nextState == null) return null

        // Find the branch name
        val branchName = nextNode.children?.find { it.name == node.name }?.name
            ?: throw IllegalStateException("Fork - can not find ${node.name} in ${nextNode.children?.joinToString { it.name }}")

        return ForkBranchCompleted(
            nodeStack = nodeStack,
            branchName = branchName,
            output = nextInput,
            completedAt = Clock.System.now(),
        )
    }

    /**
     * Handles the completion of a "foreach" iteration in a node processing workflow. It verifies whether
     * the provided node represents a `ListenTask` and whether the node is being re-entered with an existing
     * state. If these conditions are met, it creates and returns a `ListenForEachCompleted` event.
     */
    private fun listenForEachCompleted(
        nodeStack: NodeStack,
        nextNode: Node<*>,
        nextInput: JsonElement,
    ): ListenForEachCompleted? {
        val nextState = nodeStack[nextNode.position]

        // if nextNode is not a listen task, or if we are entering for the first time
        if (nextNode.task !is ListenTask || nextState == null) return null

        // This is a foreach iteration completing
        // The iteration index is tracked by the runner (ListenerModel.foreachCurrentIndex)
        return ListenForEachCompleted(
            nodeStack = nodeStack,
            iterationOutput = nextInput,
            iterationIndex = 0
        )
    }

    // ========================================
    // Instance Methods (Scope-Dependent Operations)
    // ========================================

    /**
     * Check if condition for conditional execution.
     */
    private fun checkIf(rawInput: JsonElement, scope: Scope): Boolean {
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
    private fun validateOutput(transformedOutput: JsonElement) {
        node.task.output?.schema?.let { schema ->
            validate(transformedOutput, schema)
        }
    }

    /**
     * Get flow directive from definition.
     */
    internal fun getFlowDirective(): FlowDirective? {
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
