// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.errors.WaitWorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.RootTask
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.orchestrator.context.merge
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
import com.lemline.core.states.NodeState
import com.lemline.core.states.States
import com.lemline.core.states.TryState
import com.lemline.core.states.updateWith
import com.lemline.core.workflows.toJava
import com.lemline.core.workflows.toKotlin
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
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Complete workflow orchestrator that executes workflows from start to finish.
 *
 * This orchestrator implements the standard workflow execution model:
 * - Activities execute via their processors (real HTTP calls, shell commands)
 * - Delays actually wait using coroutines
 * - Sub-workflows execute inline recursively (await=true) or fire-and-forget (await=false)
 * - Returns final workflow output
 *
 * ## Pure Functional Model
 *
 * State is **external to nodes** - stored in `Map<NodePosition, NodeState>`.
 * All functions are pure - they take immutable inputs and return new values:
 *
 * ```
 * while current is not null:
 *     (next, dataset, deltaStates, flowDirective) = run(current, dataset, states, flowDirective)
 *     states = applyDelta(states, deltaStates)  // Apply changes atomically
 *     current = next
 * ```
 *
 * ## No Cloning Needed
 *
 * Since run() is pure:
 * - It never mutates the states map
 * - It returns deltaStates map showing changes
 * - If it throws exception, states is unchanged
 * - applyDelta() creates new map when it succeeds
 *
 * ## Dataset Flow
 *
 * The dataset flows functionally as parameters (never stored):
 * - Down: Parent output → child input
 * - Up: Child output → parent input
 * - Transformed at node boundaries (input.from, output.as)
 *
 * ## Usage
 *
 * - **Synchronous testing**: Test workflows without distributed infrastructure
 * - **Single-node execution**: Run workflows on a single machine
 *
 * For distributed execution with pause/resume, use PausableOrchestrator instead.
 *
 * ## Example
 *
 * ```kotlin
 * val workflow = buildNodeInstance(definition)
 * val output = CompleteOrchestrator.run(workflow, input)
 * ```
 */
@ExperimentalTime
object WorkflowOrchestrator {

    private val logger = logger()

    /**
     * Initiates the execution of a workflow by retrieving its root node and starting
     * the processing task based on the provided input and execution mode.
     *
     * @param namespace The namespace of the workflow to execute.
     * @param name The name of the workflow to execute.
     * @param version The version of the workflow to execute.
     * @param input The input data in the form of a `JsonElement` to begin the workflow.
     * @param executionMode Determines the execution mode (e.g., CONTINUOUS, TASK_BY_TASK, etc.).
     * @return A `WorkflowResult` which represents the state or result of the workflow execution,
     * including completion, failure, or next task to process.
     */
    @JvmStatic
    suspend fun start(
        namespace: String,
        name: String,
        version: String,
        input: JsonElement,
        executionMode: ExecutionMode
    ): WorkflowState {

        val workflow = DefinitionCache.getWorkflow(namespace, name, version)
            ?: throw IllegalStateException("Workflow definition not found: $namespace/$name/$version")


        val rootNode = DefinitionCache.getRootNode(workflow)

        return resumeFromTask(
            states = mutableMapOf(),
            node = rootNode,
            rawInput = input,
            flowDirective = null,
            executionMode = executionMode
        )
    }

    @JvmStatic
    suspend fun resume(
        namespace: String,
        name: String,
        version: String,
        state: WorkflowState,
        executionMode: ExecutionMode
    ): WorkflowState {

        val workflow = DefinitionCache.getWorkflow(namespace, name, version)
            ?: throw IllegalStateException("Workflow definition not found: $namespace/$name/$version")

        val nodesMap = DefinitionCache.getNodesMap(workflow)
            ?: throw IllegalStateException("Nodes not found for workflow: $namespace/$name/$version")

        fun NodePosition.node(): Node<*> = nodesMap[this]
            ?: throw IllegalStateException("Node not found at position $this in workflow: $namespace/$name/$version")

        return when (state) {
            is WorkflowState.Completed -> state
            is WorkflowState.Failed -> when {
                state.rawInput != null -> resumeFromTask(
                    states = state.states,
                    node = state.nodePosition.node(),
                    rawInput = state.rawInput,
                    flowDirective = state.flowDirective?.toJava(),
                    executionMode = executionMode
                )

                state.rawOutput != null -> resumeFromInterruptedTask(
                    states = state.states,
                    node = state.nodePosition.node(),
                    rawOutput = state.rawOutput,
                    executionMode = executionMode
                )

                else -> throw IllegalStateException("rawInput or rawOutput is required for running $state")
            }

            is WorkflowState.ReadyForNextTask -> resumeFromTask(
                states = state.states,
                node = state.nextNodePosition.node(),
                rawInput = state.nextRawInput,
                flowDirective = state.nextFlowDirective?.toJava(),
                executionMode = executionMode
            )

            is WorkflowState.WaitingToRetry -> resumeFromTask(
                states = state.states,
                node = state.nodePosition.node(),
                rawInput = state.rawInput,
                flowDirective = state.flowDirective?.toJava(),
                executionMode = executionMode
            )

            is WorkflowState.Waiting -> resumeFromInterruptedTask(
                states = state.states,
                node = state.nodePosition.node(),
                rawOutput = state.rawOutput,
                executionMode = executionMode
            )

            is WorkflowState.RunningChildWorkflow -> resumeFromInterruptedTask(
                states = state.states,
                node = state.nodePosition.node(),
                rawOutput = state.rawOutput ?: throw IllegalStateException("rawOutput is required for running $state"),
                executionMode = executionMode
            )
        }
    }

    /**
     * Resume the workflow from a given node.
     *
     * This is the main entry point for workflow execution. It runs the execution
     * loop until the workflow completes (current becomes null).
     *
     * @param node The current node to execute
     * @param rawInput The initial input dataset
     * @return The final output dataset
     * @throws Exception if any error occurs during execution
     */
    internal suspend fun resumeFromTask(
        states: States = mapOf(),
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
        executionMode: ExecutionMode
    ): WorkflowState {
        logger.debug { "resumeFromTask node=${node.reference}, input=$rawInput, flow=$flowDirective, states=$states" }

        try {
            val result = try {
                tryCatch(node, states) {
                    runStep(node, rawInput, states.toMap(), flowDirective)
                }
            } catch (e: ChildWorkflowException) {
                // Sub-workflow needs to be started
                if (executionMode.isAsync()) return WorkflowState.RunningChildWorkflow(
                    states = states.toMap(),
                    nodePosition = node.position,
                    rawOutput = e.transformedInput,
                    childConfig = e.config,
                )
                // run the child workflow
                tryCatch(node, states) {
                    processChildWorkflowException(e, node, states.toMap(), executionMode)
                }
            } catch (e: WaitWorkflowException) {
                if (executionMode.isAsync()) return WorkflowState.Waiting(
                    states = states.toMap(),
                    nodePosition = node.position,
                    rawOutput = e.transformedInput,
                    duration = e.config.duration,
                )
                // run the wait
                tryCatch(node, states) {
                    processWaitException(e, node, states.toMap())
                }
            }

            // Create new states map with updated state updates and context exports
            val newStates = states.updateWith(result.stateUpdates, result.newContext)

            when (val delay = result.delay) {
                // Task completed
                null -> if (executionMode.stopAfterTaskCompletion(node) && result.nextNode != null)
                    return WorkflowState.ReadyForNextTask(
                        states = newStates,
                        nextNodePosition = result.nextNode.position,
                        nextRawInput = result.rawInput,
                        nextFlowDirective = result.flowDirective?.toKotlin(),
                    )
                // Task retried
                else -> {
                    if (executionMode.isAsync()) return WorkflowState.WaitingToRetry(
                        states = newStates,
                        nodePosition = result.nextNode!!.position,
                        rawInput = result.rawInput,
                        flowDirective = result.flowDirective?.toKotlin(),
                        duration = delay
                    )
                    // wait before retry
                    executeDelay(delay, "Retrying at node: ${node.name} after")
                }
            }

            if (result.nextNode == null) {
                logger.debug { "Workflow completed with output: $rawInput" }
                return WorkflowState.Completed(output = rawInput)
            }

            // Continue with the next iteration
            return resumeFromTask(
                newStates,
                result.nextNode,
                result.rawInput,
                result.flowDirective,
                executionMode = executionMode
            )

        } catch (e: Exception) {
            return WorkflowState.Failed(
                states = states.toMap(),
                nodePosition = node.position,
                rawInput = rawInput,
                rawOutput = null,
                flowDirective = flowDirective?.toKotlin(),
                exception = e
            )
        }
    }

    suspend fun resumeFromInterruptedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        states: States,
        executionMode: ExecutionMode
    ): WorkflowState = run {
        logger.debug { "resumeFromInterruptedTask In: node=${node.reference}, output=$rawOutput, states=$states" }

        try {
            // Complete the interrupted task (transforms output, updates state)
            val result = tryCatch(node, states) {
                completeInterruptedTask(node, rawOutput, states)
            }

            // Create new states map with updated state updates and context exports
            val newStates = states.updateWith(result.stateUpdates, result.newContext)

            // Continue execution from the next node (may pause again or complete)
            return@run resumeFromTask(
                states = newStates,
                node = result.nextNode ?: return WorkflowState.Completed(result.rawInput),
                rawInput = result.rawInput,
                flowDirective = result.flowDirective,
                executionMode = executionMode
            )
        } catch (e: Exception) {
            return@run WorkflowState.Failed(
                states = states,
                nodePosition = node.position,
                rawInput = null,
                rawOutput = rawOutput,
                flowDirective = null,
                exception = e
            )
        }
    }.also {
        logger.debug { "resumeFromInterruptedTask Out: state=$it" }
    }

    /**
     * Executes a given block of code within a try-catch construct.
     * If the block execution succeeds, the result is returned.
     * If it fails with an `InternalWorkflowException`, the workflow is trying to resume through a parent `try` node.
     */
    suspend fun tryCatch(
        current: Node<*>,
        states: States,
        block: suspend () -> StepResult
    ): StepResult = try {
        block()
    } catch (e: InternalWorkflowException) {
        processInternalWorkflowException(e, current, states)
    }

    private suspend fun processWaitException(
        exception: WaitWorkflowException,
        current: Node<*>,
        states: States
    ): StepResult {
        // waiting
        executeDelay(exception.config.duration, "Waiting at node: ${current.name} for")
        // complete the wait task
        return completeInterruptedTask(current, exception.transformedInput, states)
    }

    /**
     * Processes starting a child workflow.
     */
    private suspend fun processChildWorkflowException(
        exception: ChildWorkflowException,
        current: Node<*>,
        states: States,
        mode: ExecutionMode
    ): StepResult {
        val config = exception.config
        // Resolve the sub-workflow definition from the cache
        val subRootNode = resolveSubWorkflowRootNode(config)
        // execute the sub-workflow synchronously or asynchronously
        val childOutput = if (config.sync) {
            executeChildWorkflowSync(config, subRootNode, mode)
        } else {
            executeChildWorkflowAsync(exception, subRootNode, mode)
        }
        // complete the child workflow task
        return completeInterruptedTask(current, childOutput, states)
    }

    /**
     * Resolves the root node of a sub-workflow from the definition cache.
     */
    private fun resolveSubWorkflowRootNode(config: ChildWorkflowException.Config): Node<*> {
        val childWorkflowName by lazy {
            "(namespace=${config.namespace}, name=${config.name}, version=${config.version})"
        }

        val childWorkflow = DefinitionCache.getWorkflow(
            namespace = config.namespace,
            name = config.name,
            version = config.version
        ) ?: throw IllegalStateException(
            "Workflow definition not found for sub-workflow: $childWorkflowName"
        )
        return DefinitionCache.getRootNode(childWorkflow)
    }

    /**
     * Executes a child workflow synchronously.
     */
    private suspend fun executeChildWorkflowSync(
        config: ChildWorkflowException.Config,
        subWorkflowRootNode: Node<*>,
        mode: ExecutionMode
    ): JsonElement {
        logger.debug { "Executing child workflow inline: ${config.name}" }
        return when (val output = resumeFromTask(
            node = subWorkflowRootNode,
            rawInput = config.input,
            executionMode = mode
        )) {
            is WorkflowState.Completed -> {
                logger.debug { "Child workflow completed, continuing parent" }
                output.output
            }

            is WorkflowState.Failed -> {
                logger.debug { "Child workflow failed, continuing parent" }
                throw output.exception!!
            }

            else -> throw IllegalStateException("Unexpected output type: ${output::class.simpleName} for child workflow $config")
        }
    }

    /**
     * Executes a child workflow asynchronously.
     */
    private suspend fun executeChildWorkflowAsync(
        exception: ChildWorkflowException,
        subWorkflowRootNode: Node<*>,
        mode: ExecutionMode
    ): JsonElement {
        logger.debug { "Launching child workflow asynchronously (fire-and-forget): ${exception.config.name}" }
        CoroutineScope(currentCoroutineContext()).launch {
            runCatching {
                resumeFromTask(
                    node = subWorkflowRootNode,
                    rawInput = exception.config.input,
                    executionMode = mode
                )
            }.onSuccess {
                logger.debug { "Async child workflow completed successfully" }
            }.onFailure { ex ->
                logger.error(ex) { "Async child workflow failed" }
            }
        }
        return exception.transformedInput!! // Use current input for fire-and-forget
    }

    /**
     * Handles a delay operation
     */
    suspend fun executeDelay(delayDuration: Duration, logMessage: String) {
        logger.debug { "$logMessage: $delayDuration" }
        delay(delayDuration)
        logger.debug { "Delay completed" }
    }

    /**
     * Execute a single step of workflow execution - pure function.
     *
     * Determines whether we enter the node for the first time (no state)
     * or re-enter after child completion (state exists).
     *
     * @param node Current node to execute
     * @param dataset Dataset to process
     * @param states Current states map
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with: next node, dataset, deltaStates, and flow directive
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun runStep(
        node: Node<*>,
        dataset: JsonElement,
        states: States,
        flowDirective: FlowDirective?,
    ): StepResult {
        val state = states[node.position]
        val scope = getScope(node, states)
        val processor = getNodeProcessor(node)

        return if (state == null) {
            logger.debug { "Entering Down  node=${node.reference}, rawInput=$dataset" }
            // First time entering this node - pass exprArgs as parameter
            processor.enterFromParent(dataset, scope)
        } else {
            logger.debug {
                "ReEntering Up  node=${node.reference}, transformedInput=$dataset${
                    flowDirective?.get()?.let { ", flow=$it" } ?: ""
                }, state=$state"
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
    private fun <T : TaskBase> getNodeProcessor(
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
    private fun getScope(current: Node<*>, states: States): Scope =
        (states[current.position]?.scope ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getScope(it, states) })

    /**
     * Handle exception by finding a TryTask and returning the appropriate state transition.
     */
    private fun processInternalWorkflowException(
        exception: InternalWorkflowException,
        failingNode: Node<*>,
        states: States,
    ): StepResult {
        // Find the nearest TryTask that can handle this error
        var tryNode: Node<*>? = failingNode

        while (tryNode != null) {
            if (tryNode.task is TryTask) {
                @Suppress("UNCHECKED_CAST")
                tryNode as Node<TryTask>
                // current scope of the try node
                val tryScope = getScope(tryNode, states)
                // current state of the try node
                val tryState = states[tryNode.position] as TryState
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
     * Completes an interrupted task by processing the output through the current node's processor.
     *
     * @param node The current node that was interrupted
     * @param rawOutput The output from the interrupted task
     * @param states The current workflow states
     * @return StepResult containing the next node, dataset, and state updates
     */
    private fun completeInterruptedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        states: States
    ): StepResult {
        val processor = getNodeProcessor(node)
        val scope = getScope(node, states)

        return processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = processor.getFlowDirective(),
            parentScope = scope,
            taskContext = null
        )
    }

}
