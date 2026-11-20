// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonNode
import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.VALIDATION
import com.lemline.core.errors.withExecutionStack
import com.lemline.core.expressions.JQExpression
import com.lemline.core.extensions.ExtensionController
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeOrigin
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.taskScope
import com.lemline.core.processors.scope.withRawOutput
import com.lemline.core.processors.scope.withTransformedInput
import com.lemline.core.processors.scope.withTransformedOutput
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.ExtensionPhase
import com.lemline.core.states.ExtensionReturn
import com.lemline.core.states.ExtensionReturnKind
import com.lemline.core.states.NodeStack
import com.lemline.core.states.NodeState
import com.lemline.core.states.PendingTaskState
import com.lemline.core.states.PreparedTaskResult
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ForEachCompleted
import com.lemline.core.states.WorkflowEvent.ForkBranchCompleted
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.states.WorkflowEvent.WorkflowCompleted
import com.lemline.core.tasks.toJava
import com.lemline.core.tasks.toKotlin
import com.lemline.core.workflows.branches
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.api.types.SubflowInput
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.Use
import kotlin.time.Clock
import kotlin.time.Instant

abstract class AbstractProcessor<T : TaskBase, S : NodeState>(
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
    abstract fun stateWhenEnteringFromParent(transformedInput: JsonNode, scope: Scope, startedAt: Instant = Clock.System.now()): S

    /** Execute node action (for activity tasks).*/
    open suspend fun execute(
        transformedInput: JsonNode,
        scope: Scope,
        state: S,
    ): JsonNode = transformedInput

    open val isAsync: Boolean = false

    /** return started event for activities*/
    open fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonNode,
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

    /** Update state when re-entering from child.*/
    open fun stateWhenReEnteringFromChild(
        state: S,
        output: JsonNode,
        scope: Scope,
        nodeName: String? = null
    ): S {
        // Default implementation for leaf nodes - just increment visitCount
        return state
    }

    /**
     * Determine where to go next based on current state. Overridden in control flow nodes.
     * Default: go to parent (leaf node behavior - activities, switch, etc.)
     */
    open fun getNextNode(
        state: S,
        dataset: JsonNode,
        scope: Scope,
    ) = NextInfo(
        nextNode = node.parent,
        nextDirective = getFlowDirective()
    )

    // ========================================
    // Entering a Node for the first time
    // ========================================

    suspend fun enterFromParent(
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        rawInput: JsonNode,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        logger.debug { "Entering Down  node=${node.position} - ${node.task::class.simpleName}(input=$rawInput)" }

        // Validate input against schema (throws ValidationException)
        validateInput(rawInput)

        // Create execution context
        val startedAt = Clock.System.now()

        // Get current scope from states
        var scope = nodeStack.taskScope(node, startedAt, rawInput)

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

        if (node.extensions.isNotEmpty()) {
            val pending = nodeStack.push(node.position, PendingTaskState(startedAt))
            return withExecutionStack(pending) {
                val selected = node.extensions.filter { plan ->
                    plan.condition?.let { condition ->
                        try {
                            evalBoolean(transformedInput, condition, "use.extensions[${plan.index}].${plan.name}.when", scope)
                        } catch (e: InternalException) {
                            throw InternalException(e.error.copy(title = "Extension '${plan.name}' when: ${e.error.title}"))
                        }
                    } ?: true
                }
                val continuation = ExtensionController.start(selected, rawInput, transformedInput)
                continueExtension(pending.withExtension(continuation), workflowInfo, lifecycleHook)
            }
        }
        val nodeState = stateWhenEnteringFromParent(transformedInput, scope, startedAt)
        val updatedStack = nodeStack.push(node.position, nodeState)
        return withExecutionStack(updatedStack) {
            continueTo(updatedStack, workflowInfo, transformedInput, scope, lifecycleHook)
        }
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
    internal suspend fun reEnterFromChild(
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        output: JsonNode,
        flowDirective: FlowDirective?,
        lifecycleHook: LifecycleEventHook,
        extensionReturn: ExtensionReturn? = null,
    ): WorkflowEvent {
        if (extensionReturn != null) {
            ExtensionController.validateReturn(node, nodeStack.last(), extensionReturn)
            val continuation = nodeStack.currentExtension!!
            if (extensionReturn.kind == ExtensionReturnKind.END) return continueToEnd(nodeStack, workflowInfo, output, lifecycleHook)
            return when (continuation.phase) {
                ExtensionPhase.BEFORE -> if (extensionReturn.kind == ExtensionReturnKind.EXIT) {
                    completeTask(output, getFlowDirective(), nodeStack.taskScope(node), nodeStack, workflowInfo, lifecycleHook, shortCircuit = true)
                } else {
                    continueExtension(nodeStack.withExtension(ExtensionController.afterBefore(node, continuation)), workflowInfo, lifecycleHook)
                }
                ExtensionPhase.AFTER -> continueExtension(
                    nodeStack.withExtension(ExtensionController.afterAfter(node, continuation, extensionReturn.kind == ExtensionReturnKind.EXIT)), workflowInfo, lifecycleHook
                )
                else -> error("Unexpected extension return in ${continuation.phase}")
            }
        }
        require(nodeStack.currentState !is PendingTaskState && (nodeStack.currentExtension == null || nodeStack.currentExtension?.phase == ExtensionPhase.BODY)) {
            "Expected extension return at ${node.position}"
        }
        // Only the top-level synthetic block exports control to its owner.
        if (node.origin == NodeOrigin.EXTENSION_BLOCK && flowDirective?.get() in listOf(FlowDirectiveEnum.EXIT, FlowDirectiveEnum.END)) {
            return returnExtensionBlock(nodeStack, output, if (flowDirective?.get() == FlowDirectiveEnum.END) ExtensionReturnKind.END else ExtensionReturnKind.EXIT, workflowInfo, lifecycleHook)
        }
        logger.debug {
            "ReEntering Up  node=${node.position} - ${node.task::class.simpleName}(input=$output${
                flowDirective?.get()?.let { ", directive=$it" } ?: ""
            }), state=${nodeStack.currentState}"
        }
        @Suppress("UNCHECKED_CAST")
        val state = nodeStack.currentState as S
        val scope = nodeStack.taskScope(node, state.startedAt)

        val updatedState: S = stateWhenReEnteringFromChild(state, output, scope, flowDirective.nodeName)
        val updatedStack = nodeStack.updateTopState(updatedState).incrementTopCounter()

        return withExecutionStack(updatedStack) { when (val directive = flowDirective?.get()) {
            is FlowDirectiveEnum -> when (directive) {
                // END: Workflow complete - recursive unwinding
                FlowDirectiveEnum.END -> continueToEnd(
                    nodeStack = updatedStack,
                    workflowInfo = workflowInfo,
                    output = output,
                    lifecycleHook = lifecycleHook
                )
                // EXIT: exit current node
                FlowDirectiveEnum.EXIT -> continueToParent(
                    nodeStack = updatedStack,
                    workflowInfo = workflowInfo,
                    transformedInput = output,
                    currentFlowDirective = getFlowDirective(),
                    scope = scope,
                    lifecycleHook = lifecycleHook,
                )
                // CONTINUE: continue
                FlowDirectiveEnum.CONTINUE -> continueTo(
                    nodeStack = updatedStack,
                    workflowInfo = workflowInfo,
                    transformedInput = output,
                    scope = scope,
                    lifecycleHook = lifecycleHook
                )
            }

            // Goto named sibling or null
            is String, null -> continueTo(
                nodeStack = updatedStack,
                workflowInfo = workflowInfo,
                transformedInput = output,
                scope = scope,
                lifecycleHook = lifecycleHook
            )

            else -> throw IllegalArgumentException("Unknown flow directive: $directive")
        } }
    }

    private suspend fun returnExtensionBlock(stack: NodeStack, output: JsonNode, kind: ExtensionReturnKind, info: WorkflowInfo, hook: LifecycleEventHook): TaskScheduled {
        hook.onTaskCompleted(info, stack, node.position, output, Clock.System.now())
        return TaskScheduled(
            nodeStack = stack.pop().incrementTopCounter(),
            nodePosition = requireNotNull(node.parent).position,
            rawInput = output,
            flowDirective = null,
            extensionReturn = ExtensionReturn(node.position, kind),
            createdAt = Clock.System.now(),
        )
    }

    private suspend fun continueExtension(stack: NodeStack, info: WorkflowInfo, hook: LifecycleEventHook): WorkflowEvent = withExecutionStack(stack) {
        val extension = requireNotNull(stack.currentExtension)
        when (extension.phase) {
            ExtensionPhase.BEFORE, ExtensionPhase.AFTER -> {
                extension.validate(stack.currentState)
                TaskScheduled(stack, ExtensionController.block(node, extension).position,
                    if (extension.phase == ExtensionPhase.BEFORE) extension.transformedInput else extension.preparedResult!!.transformedOutput, null,
                    createdAt = Clock.System.now())
            }
            ExtensionPhase.BODY -> {
                require(stack.currentState is PendingTaskState) { "Body already initialized at ${node.position}" }
                val scope = stack.taskScope(node).withTransformedInput(extension.transformedInput)
                val state = stateWhenEnteringFromParent(extension.transformedInput, scope, stack.currentState.startedAt)
                val ready = stack.updateTopState(state)
                withExecutionStack(ready) { continueTo(ready, info, extension.transformedInput, scope, hook) }
            }
            ExtensionPhase.READY -> {
                extension.validate(stack.currentState)
                val result = requireNotNull(extension.preparedResult)
                finalizeTask(result.transformedOutput, result.pendingFlowDirective?.toJava(), stack, info, hook)
            }
        }
    }

    // ========================================
    // CONTINUE
    // ========================================
    private suspend fun continueTo(
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        transformedInput: JsonNode,
        scope: Scope,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        // Pure navigation - determine where to go next
        val navigationInfo = @Suppress("UNCHECKED_CAST") getNextNode(
            nodeStack.currentState as S,
            transformedInput,
            scope
        )
        val (nextNode, nextDirective, nextInput) = navigationInfo

        // check if we should return to parent
        @Suppress("UNCHECKED_CAST")
        return when (nextNode == node.parent) {
            // case of leaf (activities, switch, ...) OR end of a control flow
            true -> continueToParent(
                nodeStack = nodeStack,
                workflowInfo = workflowInfo,
                transformedInput = transformedInput,
                currentFlowDirective = nextDirective,
                scope = scope,
                lifecycleHook = lifecycleHook,
            )

            // control flows that are not completed (do, for, ...), going to a child
            // Use nextInput if provided (e.g., from CallFunction's with arguments),
            // otherwise default to transformedInput
            false -> continueToChild(
                childNode = nextNode,
                childRawInput = nextInput ?: transformedInput,
                nodeStack = nodeStack
            )
        }
    }

    private fun continueToChild(
        childNode: Node<*>?,
        childRawInput: JsonNode,
        nodeStack: NodeStack
    ) = getNextEvent(
        nodeStack = nodeStack,
        nextNode = childNode,
        nextInput = childRawInput,
        nextDirective = null,
    )

    // ========================================
    // EXIT
    // ========================================
    private suspend fun continueToParent(
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        transformedInput: JsonNode,
        currentFlowDirective: FlowDirective?,
        scope: Scope,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {

        // it the task is async, return started event
        if (isAsync) return startedEvent(nodeStack, transformedInput, scope)

        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        @Suppress("UNCHECKED_CAST")
        val rawOutput = execute(transformedInput, scope, nodeStack.currentState as S)

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
        rawOutput: JsonNode,
        currentFlowDirective: FlowDirective?,
        currentScope: Scope,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        shortCircuit: Boolean = false,
    ): WorkflowEvent {
        val extension = nodeStack.currentExtension
        require(extension == null || extension.phase == ExtensionPhase.BODY || (shortCircuit && extension.phase == ExtensionPhase.BEFORE)) {
            "Unexpected completion at ${node.position} in ${extension?.phase}"
        }
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

        val updatedStack = exportedContext?.let { nodeStack.withContext(it) } ?: nodeStack
        if (extension != null) {
            val result = PreparedTaskResult(rawOutput, transformedOutput, currentFlowDirective?.toKotlin())
            return continueExtension(updatedStack.withExtension(ExtensionController.prepared(node, extension, result, shortCircuit)), workflowInfo, lifecycleHook)
        }
        return finalizeTask(transformedOutput, currentFlowDirective, updatedStack, workflowInfo, lifecycleHook)
    }

    private suspend fun finalizeTask(
        transformedOutput: JsonNode,
        currentFlowDirective: FlowDirective?,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        if (node.origin == NodeOrigin.EXTENSION_BLOCK) return returnExtensionBlock(nodeStack, transformedOutput, ExtensionReturnKind.COMPLETE, workflowInfo, lifecycleHook)
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
        val updatedStack = nodeStack.pop().incrementTopCounter()

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
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        output: JsonNode,
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
            nodeStack = if (node.position == NodePosition.root) nodeStack else nodeStack.pop().incrementTopCounter(),
            nextNode = node.parent,
            nextInput = output,
            nextDirective = FlowDirective().apply { flowDirectiveEnum = FlowDirectiveEnum.END } // Pass END up the chain
        )
    }

    // ========================================
    // Next Event
    // ========================================

    private fun getNextEvent(
        nodeStack: NodeStack,
        nextNode: Node<*>?,
        nextInput: JsonNode,
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

        // Are we now completing a foreach subscription?
        forEachCompleted(nodeStack, nextNode, nextInput)?.let { return it }

        // Next Task
        return TaskScheduled(
            nodeStack = nodeStack,
            nodePosition = nextNode.position,
            rawInput = nextInput,
            flowDirective = nextDirective?.toKotlin(),
            createdAt = Clock.System.now(),
        )

    }

    /**
     * Determines if a fork branch has completed successfully based on the current node, its stack, and directive,
     * and returns the corresponding `ForkBranchCompleted` event if applicable.
     */
    private fun forkBranchCompleted(
        nodeStack: NodeStack,
        nextNode: Node<*>,
        nextInput: JsonNode,
    ): ForkBranchCompleted? {
        val nextFrame = nodeStack[nextNode.position]

        // if nextNode is not a fork task, or if we are entering for the first time
        if (nextNode.task !is ForkTask || nextFrame == null) return null

        // Find the branch name using typed accessor
        @Suppress("UNCHECKED_CAST")
        val forkNode = nextNode as Node<ForkTask>
        val branchPosition = forkNode.branches.find { it.position == node.position }?.position
            ?: error("Fork - can not find ${node.name} in [${forkNode.branches.joinToString { it.name }}]")

        return ForkBranchCompleted(
            nodeStack = nodeStack,
            branchPosition = branchPosition,
            branchOutput = nextInput,
            completedAt = Clock.System.now(),
        )
    }

    /**
     * Handles the completion of a "foreach" iteration in a node processing workflow. It verifies whether
     * the provided node represents a [ForeachTask] and whether the node is being re-entered with an existing
     * state. If these conditions are met, it creates and returns a [ForEachCompleted] event.
     */
    private fun forEachCompleted(
        nodeStack: NodeStack,
        nextNode: Node<*>,
        nextInput: JsonNode,
    ): ForEachCompleted? {
        val nextFrame = nodeStack[nextNode.position]

        // if nextNode is not a foreach task, or if we are entering for the first time
        if (nextNode.task !is ForeachTask || nextFrame == null) return null

        return ForEachCompleted(
            nodeStack = nodeStack,
            output = nextInput,
        )
    }

    // ========================================
    // Instance Methods (Scope-Dependent Operations)
    // ========================================

    /**
     * Check if condition for conditional execution.
     */
    private fun checkIf(rawInput: JsonNode, scope: Scope): Boolean {
        val ifCondition = node.task.`if` ?: return true
        return evalBoolean(rawInput, ifCondition, "if", scope)
    }

    /**
     * Validate input against schema.
     */
    private fun validateInput(rawInput: JsonNode) {
        node.task.input?.schema?.let { schema ->
            validate(rawInput, schema)
        }
    }

    /**
     * Transform input using input.from expression.
     */
    private fun transformInput(rawInput: JsonNode, scope: Scope): JsonNode {
        return inputFrom(rawInput, node.task.input?.from, scope)
    }

    /**
     * Transforms the output dataset using the task's `output.as` expression.
     */
    private fun transformOutput(rawOutput: JsonNode, scope: Scope): JsonNode {
        return outputAs(rawOutput, node.task.output?.`as`, scope)
    }

    /**
     * Exports data to workflow context using the task's `export.as` expression.
     */
    private fun exportToContext(transformedOutput: JsonNode, scope: Scope): ObjectNode? {
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
    private fun validateOutput(transformedOutput: JsonNode) {
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

    private fun validate(data: JsonNode, schemaUnion: SchemaUnion) = try {
        SchemaValidator.validate(data, schemaUnion)
    } catch (e: Exception) {
        raiseError(VALIDATION, e.message, e.stackTraceToString())
    }

    // ========================================
    // Expression Evaluation Helpers
    // ========================================

    private fun inputFrom(data: JsonNode, inputFrom: InputFrom?, scope: ObjectNode): JsonNode =
        inputFrom?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun outputAs(data: JsonNode, outputAs: OutputAs?, scope: ObjectNode): JsonNode =
        outputAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data

    private fun exportAs(data: JsonNode, exportAs: ExportAs?, scope: ObjectNode): ObjectNode =
        (exportAs?.let { eval(data, LemlineJson.encodeToElement(it), scope, true) } ?: data).let {
            when (it is ObjectNode) {
                true -> it
                false -> raiseError(EXPRESSION, "'.export.as' expression must be an object, but is '$it'")
            }
        }

    internal fun runWorkflowInput(data: JsonNode, subFlowInput: SubflowInput?, scope: ObjectNode): JsonNode =
        subFlowInput?.let { eval(data, it.additionalProperties.toJsonNode(), scope, false) } ?: data

    internal fun evalBoolean(
        data: JsonNode,
        expr: String,
        name: String,
        scope: ObjectNode
    ): Boolean = eval(data, expr, scope).let {
        when (it is ValueNode && it.booleanOrNull != null) {
            true -> it.boolean
            false -> raiseError(EXPRESSION, "'.$name' expression must be a boolean, but is '$it'")
        }
    }

    internal fun evalString(
        data: JsonNode,
        expr: String,
        name: String,
        scope: ObjectNode
    ): String = eval(data, expr, scope).let {
        when (it is ValueNode) {
            true -> it.content
            false -> raiseError(EXPRESSION, "'.$name' expression must be a String, but is '$it'")
        }
    }

    internal fun evalList(
        data: JsonNode,
        expr: String,
        name: String,
        scope: ObjectNode
    ) = eval(data, expr, scope).let {
        when (it is ArrayNode) {
            true -> it.toList()
            false -> raiseError(EXPRESSION, "'.$name' expression must be an array, but is '$it'")
        }
    }

    private fun eval(data: JsonNode, expr: String, scope: ObjectNode): JsonNode = try {
        JQExpression.eval(data, jsonValue(expr), scope, false, node.evaluationMode)
    } catch (e: Exception) {
        raiseError(EXPRESSION, e.message, e.stackTraceToString())
    }

    protected fun eval(
        data: JsonNode,
        expr: JsonNode,
        scope: ObjectNode,
        force: Boolean = false
    ) = try {
        JQExpression.eval(data, expr, scope, force, node.evaluationMode)
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
 * - A `JsonNode?`: Optional input for the next node (overrides default transformedInput)
 *   Used by CallFunction to pass evaluated `with` arguments to the function.
 */
data class NextInfo(
    val nextNode: Node<*>?,
    val nextDirective: FlowDirective?,
    val nextInput: JsonNode? = null
)
