// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution

import com.lemline.common.logger.logger
import com.lemline.core.errors.WorkflowException
import com.lemline.core.execution.context.Scope
import com.lemline.core.execution.context.merge
import com.lemline.core.execution.models.StepResult
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.CallHttpProcessor
import com.lemline.core.processors.DoProcessor
import com.lemline.core.processors.ForProcessor
import com.lemline.core.processors.NodeProcessor
import com.lemline.core.processors.RaiseProcessor
import com.lemline.core.processors.RootProcessor
import com.lemline.core.processors.RunScriptProcessor
import com.lemline.core.processors.RunShellProcessor
import com.lemline.core.processors.RunWorkflowProcessor
import com.lemline.core.processors.SetProcessor
import com.lemline.core.processors.SwitchProcessor
import com.lemline.core.processors.TryProcessor
import com.lemline.core.processors.WaitProcessor
import com.lemline.core.states.MutableStates
import com.lemline.core.states.NodeState
import com.lemline.core.states.RootState
import com.lemline.core.states.States
import com.lemline.core.states.TryState
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunWorkflow
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.WaitTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Base class providing shared functionality for workflow orchestrators.
 *
 * This abstract class contains common helper methods used by both CompleteOrchestrator
 * (which executes workflows to completion) and PausableOrchestrator (which can pause
 * at specific boundaries).
 *
 * ## Shared Functionality
 *
 * - **runStep**: Execute a single step (enter or re-enter a node)
 * - **getNodeProcessor**: Get the appropriate processor for a node type
 * - **getScope**: Build scope by merging node state with parent scopes
 * - **handleException**: Find and invoke error handlers (try/catch/retry)
 * - **updateRootContext**: Update exported workflow context
 * - **completeChildWorkflowTask**: Complete a sub-workflow task with output
 */
abstract class BaseOrchestrator {

    protected val logger = logger()

    /**
     * Execute a single step of workflow execution - pure function.
     *
     * Determines whether to enter node for first time (no state in map)
     * or re-enter after child completion (state exists in map).
     *
     * @param node Current node to execute
     * @param dataset Dataset to process
     * @param states Current states map
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with next node, dataset, deltaStates, and flow directive
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TaskBase> runStep(
        node: Node<T>,
        dataset: JsonElement,
        states: States,
        flowDirective: FlowDirective?,
    ): StepResult {
        val state = states[node]
        val scope = getScope(node, states)
        val processor = getNodeProcessor(node)

        return if (state == null) {
            logger.debug { "Entering node: ${node.reference} with input: $dataset" }
            // First time entering this node - pass exprArgs as parameter
            processor.enterFromParent(dataset, scope)
        } else {
            logger.debug {
                "Back to node:  '${node.reference}' with data: '$dataset'${
                    flowDirective?.get()?.let { " and directive '$it'" } ?: ""
                }"
            }
            // Re-entering after a child completed
            // Safe cast: state was created by the same processor type, so types match
            processor.enterFromChild(state, flowDirective, dataset, scope)
        }
    }

    /**
     * Retrieves the appropriate `NodeProcessor` for the given `Node` based on the type of task associated with the node.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : TaskBase> getNodeProcessor(
        node: Node<T>
    ): NodeProcessor<T, NodeState> {
        return when (node.task) {
            is RootTask -> RootProcessor(node as Node<RootTask>)
            is DoTask -> DoProcessor(node as Node<DoTask>)
            is ForTask -> ForProcessor(node as Node<ForTask>)
            is SetTask -> SetProcessor(node as Node<SetTask>)
            is SwitchTask -> SwitchProcessor(node as Node<SwitchTask>)
            is TryTask -> TryProcessor(node as Node<TryTask>)
            is RaiseTask -> RaiseProcessor(node as Node<RaiseTask>)
            is CallHTTP -> CallHttpProcessor(node as Node<CallHTTP>)
            is WaitTask -> WaitProcessor(node as Node<WaitTask>)
            is RunTask -> {
                // Dispatch to appropriate run processor based on run configuration type
                val runTask = node as Node<RunTask>
                when (runTask.task.run.get()) {
                    is RunShell -> RunShellProcessor(runTask)
                    is RunScript -> RunScriptProcessor(runTask)
                    is RunWorkflow -> RunWorkflowProcessor(runTask)
                    else -> throw IllegalArgumentException("Unknown run task type: ${runTask.task.run.get()?.javaClass?.simpleName}")
                }
            }

            else -> throw IllegalArgumentException("Unknown task type: ${node.task::class.simpleName}")
        } as NodeProcessor<T, NodeState>
    }

    /**
     * Retrieves the `Scope` associated with the given node by combining its own expression arguments
     * with those of its parent nodes in the tree, if present.
     */
    protected fun getScope(current: Node<*>, states: States): Scope =
        (states[current]?.scope ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getScope(it, states) })

    /**
     * Handle exception by finding a TryTask and returning appropriate state transition.
     *
     * This is a pure function that converts an exception into a StepResult with explicit
     * state deltas for retry or catch behavior.
     *
     * @param failingNode Node where exception occurred
     * @param exception WorkflowException that was thrown
     * @param states Current states map (immutable)
     * @return StepResult with state deltas for retry or catch
     *
     * @throws WorkflowException if no handler found
     */
    protected fun handleException(
        failingNode: Node<*>,
        exception: WorkflowException,
        states: States,
    ): StepResult {
        // Find nearest TryTask that can handle this error
        var tryNode: Node<*>? = failingNode

        while (tryNode != null) {
            if (tryNode.task is TryTask) {
                @Suppress("UNCHECKED_CAST")
                tryNode as Node<TryTask>
                // current scope of the try node
                val tryScope = getScope(tryNode, states)
                // current state of the try node
                val tryState = states[tryNode] as TryState
                // build a processor for the try node
                val processor = getNodeProcessor(tryNode) as TryProcessor
                // check that this node actually can handle this error
                if (processor.isCatching(exception.error, tryState, tryScope)) {
                    return processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryState,
                        scope = tryScope
                    )
                }
            }
            tryNode = tryNode.parent
        }
        // No handler found - fail workflow
        throw exception
    }

    /**
     * Updates the RootState context with the new context.
     *
     * @param node The current node of the workflow
     * @param states The mutable states map
     * @param newContext The new context data
     */
    protected fun updateRootContext(node: Node<*>?, states: MutableStates, newContext: JsonObject) {
        if (node == null) return

        var rootNode: Node<*> = node
        while (rootNode.parent != null) rootNode = rootNode.parent

        // Get the current root state (must exist, as root is always entered first)
        when (val rootState = states[rootNode]) {
            null -> throw IllegalStateException("RootState not found for node '${rootNode.name}' - workflow not properly initialized")
            is RootState -> {
                // Replace context with new context (as per Serverless Workflow spec)
                states[rootNode] = rootState.copyWithContext(newContext)
            }

            else -> throw IllegalStateException("State of root node ${rootNode.reference}, not a RootState: $rootState")
        }
    }

    /**
     * Completes a child workflow task by processing the output through the current node's processor.
     * This method handles the common task completion logic for both synchronous and asynchronous
     * child workflow executions.
     *
     * @param currentNode The current node that initiated the child workflow
     * @param childOutput The output from the child workflow execution
     * @param states The current workflow states
     * @return StepResult containing the next node, dataset, and state updates
     */
    protected fun completeChildWorkflowTask(
        currentNode: Node<*>,
        childOutput: JsonElement,
        states: Map<Node<*>, NodeState>
    ): StepResult {
        val processor = getNodeProcessor(currentNode)
        val scope = getScope(currentNode, states)
        return processor.completeTask(
            rawOutput = childOutput,
            currentFlowDirective = processor.getFlowDirective(),
            parentScope = scope,
            taskContext = null
        )
    }
}
