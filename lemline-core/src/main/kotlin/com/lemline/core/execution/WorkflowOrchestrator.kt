package com.lemline.core.execution

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.errors.WaitWorkflowException
import com.lemline.core.execution.context.Scope
import com.lemline.core.execution.context.merge
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
import com.lemline.core.states.States
import com.lemline.core.states.TryState
import com.lemline.core.states.replaceContext
import com.lemline.core.states.updateWith
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
     * Execute workflow from input to completion with external state management.
     *
     * This is the main entry point for workflow execution. It runs the execution
     * loop until the workflow completes (current becomes null).
     *
     * @param node The current node to execute
     * @param input The initial input dataset
     * @return The final output dataset
     * @throws Exception if any error occurs during execution
     */
    suspend fun resumeFromTask(
        node: Node<*>,
        input: JsonElement,
        states: MutableStates = mutableMapOf(),
        mode: ExecutionMode
    ): WorkflowResult {
        logger.debug { "Resuming from node: ${node.reference}" }

        var current: Node<*>? = node
        var rawInput: JsonElement = input
        var flowDirective: FlowDirective? = null

        try {
            while (current != null) {
                val result = try {
                    tryCatch(current, states) {
                        runStep(current!!, rawInput, states.toMap(), flowDirective)
                    }
                } catch (e: ChildWorkflowException) {
                    // Sub-workflow needs to be started
                    if (mode.stopIfAsync()) return WorkflowResult.RunWorkflow(
                        states = states.toMap(),
                        node = current,
                        transformedInput = e.transformedInput,
                        childConfig = e.config,
                    )
                    // run the child workflow
                    tryCatch(current, states) {
                        processChildWorkflowException(e, current!!, states.toMap(), mode)
                    }
                } catch (e: WaitWorkflowException) {
                    if (mode.stopIfAsync()) return WorkflowResult.Wait(
                        states = states.toMap(),
                        node = current,
                        rawOutput = e.transformedInput,
                        duration = e.config.duration,
                    )
                    // run the wait
                    tryCatch(current, states) {
                        processWaitException(e, current!!, states.toMap())
                    }
                }
                // update states
                states.updateWith(result.stateUpdates)
                // update context if needed
                states.replaceContext(result.newContext)

                when (val delay = result.delay) {
                    // Task completed
                    null -> if (mode.stopAfterTaskCompletion(current)) return WorkflowResult.TaskCompleted(
                        states = states,
                        nextNode = result.nextNode!!,
                        output = result.rawInput
                    )
                    // Task retried
                    else -> {
                        if (mode.stopIfAsync()) return WorkflowResult.Retry(
                            states = states.toMap(),
                            node = result.nextNode!!,
                            rawInput = result.rawInput,
                            duration = delay
                        )
                        // wait before retry
                        executeDelay(delay, "Retrying at node: ${current.name} after")
                    }
                }

                current = result.nextNode
                rawInput = result.rawInput
                flowDirective = result.flowDirective
            }

            logger.debug { "Workflow completed with output: $rawInput" }
            return WorkflowResult.WorkflowCompleted(output = rawInput)
        } catch (e: Exception) {
            return WorkflowResult.WorkflowFailed(
                states = states.toMap(),
                node = current!!,
                rawInput = rawInput,
                rawOutput = null,
                error = e
            )
        }
    }

    suspend fun resumeFromInterruptedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        states: MutableStates,
        mode: ExecutionMode
    ): WorkflowResult {
        logger.debug { "Resuming after execution for node: ${node.reference}" }

        try {
            // Complete the interrupted task (transforms output, updates state)
            val result = tryCatch(node, states) {
                completeInterruptedTask(node, rawOutput, states.toMap())
            }

            // Apply state updates from completion
            states.updateWith(result.stateUpdates)

            // Handle any context exports
            states.replaceContext(result.newContext)

            // Continue execution from the next node (may pause again or complete)
            return resumeFromTask(
                node = result.nextNode ?: return WorkflowResult.WorkflowCompleted(result.rawInput),
                input = result.rawInput,
                states = states,
                mode
            )
        } catch (e: Exception) {
            return WorkflowResult.WorkflowFailed(
                states = states.toMap(),
                node = node,
                rawInput = null,
                rawOutput = rawOutput,
                error = e
            )
        }
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
        val childOutput = if (config.awaitCompletion) {
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
    private fun resolveSubWorkflowRootNode(exception: ChildWorkflowException.Config): Node<*> {
        val namespace = WorkflowNamespace(exception.namespace)
        val name = WorkflowName(exception.name)
        val version = WorkflowVersion(exception.version)

        logger.debug { "Resolving sub-workflow: namespace=$namespace, name=$name, version=$version" }

        val subWorkflow = DefinitionCache.getOrNull(namespace, name, version)
            ?: throw IllegalStateException(
                "Sub-workflow not found: namespace=$namespace, name=$name, version=$version"
            )

        return DefinitionCache.getRootNode(subWorkflow)
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
        return when (val output = resumeFromTask(subWorkflowRootNode, config.rawInput, mutableMapOf(), mode)) {
            is WorkflowResult.WorkflowCompleted -> {
                logger.debug { "Child workflow completed, continuing parent" }
                output.output
            }

            is WorkflowResult.WorkflowFailed -> {
                logger.debug { "Child workflow failed, continuing parent" }
                throw output.error
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
                resumeFromTask(subWorkflowRootNode, exception.config.rawInput, mutableMapOf(), mode)
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
        (states[current]?.scope ?: buildJsonObject { })
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
