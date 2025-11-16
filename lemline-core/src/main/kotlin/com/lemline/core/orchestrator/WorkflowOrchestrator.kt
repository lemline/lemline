// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.errors.ForkException
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
import com.lemline.core.processors.ForkProcessor
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
import com.lemline.core.states.BranchExecution
import com.lemline.core.states.BranchState
import com.lemline.core.states.BranchStatus
import com.lemline.core.states.ForkConfig
import com.lemline.core.states.ForkTaskState
import com.lemline.core.states.TaskState
import com.lemline.core.states.TaskStates
import com.lemline.core.states.TryTaskState
import com.lemline.core.states.WorkflowState
import com.lemline.core.states.updateWith
import com.lemline.core.workflows.toJava
import com.lemline.core.workflows.toKotlin
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
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
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
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
     * Resumes the execution of a workflow from a given state, continuing the execution based
     * on the current workflow state and execution mode provided.
     *
     * @param workflow The workflow object representing the structure and logic of the process.
     * @param state The current state of the workflow that determines how execution should proceed.
     * @param executionMode The mode of execution to follow during the workflow process.
     * @return The new state of the workflow after resuming its execution.
     */
    suspend fun resume(
        workflow: Workflow,
        state: WorkflowState,
        executionMode: ExecutionMode
    ): WorkflowState {
        val nodesMap = DefinitionCache.getNodesMap(workflow)

        fun NodePosition.node(): Node<*> = nodesMap[this]
            ?: throw IllegalStateException("Node not found at position $this in workflow: ${workflow.namespace}/${workflow.name}/${workflow.version}")

        return when (state) {
            is WorkflowState.Starting -> {
                val rootNode = DefinitionCache.getRootNode(workflow)

                resumeFromTask(
                    taskStates = mutableMapOf(),
                    node = rootNode,
                    rawInput = state.input,
                    flowDirective = null,
                    executionMode = executionMode
                )
            }

            is WorkflowState.Completed -> state
            is WorkflowState.Failed -> when {
                state.rawInput != null -> resumeFromTask(
                    taskStates = state.taskStates,
                    node = state.nodePosition.node(),
                    rawInput = state.rawInput,
                    flowDirective = state.flowDirective?.toJava(),
                    executionMode = executionMode
                )

                state.rawOutput != null -> resumeFromInterruptedTask(
                    taskStates = state.taskStates,
                    node = state.nodePosition.node(),
                    rawOutput = state.rawOutput,
                    executionMode = executionMode
                )

                else -> throw IllegalStateException("rawInput or rawOutput is required for running $state")
            }

            is WorkflowState.ReadyForNextTask -> resumeFromTask(
                taskStates = state.taskStates,
                node = state.nodePosition.node(),
                rawInput = state.rawInput,
                flowDirective = state.flowDirective?.toJava(),
                executionMode = executionMode
            )

            is WorkflowState.Retrying -> resumeFromTask(
                taskStates = state.taskStates,
                node = state.nodePosition.node(),
                rawInput = state.rawInput,
                flowDirective = state.flowDirective?.toJava(),
                executionMode = executionMode
            )

            is WorkflowState.Waiting -> resumeFromInterruptedTask(
                taskStates = state.taskStates,
                node = state.nodePosition.node(),
                rawOutput = state.rawOutput,
                executionMode = executionMode
            )

            is WorkflowState.RunningChildWorkflow -> resumeFromInterruptedTask(
                taskStates = state.taskStates,
                node = state.nodePosition.node(),
                rawOutput = state.rawOutput ?: throw IllegalStateException("rawOutput is required for running $state"),
                executionMode = executionMode
            )

            is WorkflowState.RunningFork -> resumeFromFork(
                workflow = workflow,
                state = state,
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
        taskStates: TaskStates = mapOf(),
        node: Node<*>,
        rawInput: JsonElement,
        flowDirective: FlowDirective? = null,
        executionMode: ExecutionMode
    ): WorkflowState {
        logger.debug { "resumeFromTask node=${node.reference}, input=$rawInput, flow=$flowDirective, states=$taskStates" }

        try {
            val result = try {
                tryCatch(node, taskStates) {
                    runStep(node, rawInput, taskStates.toMap(), flowDirective)
                }
            } catch (e: ChildWorkflowException) {
                // Sub-workflow needs to be started
                if (executionMode.isAsync()) return WorkflowState.RunningChildWorkflow(
                    taskStates = taskStates.toMap(),
                    nodePosition = node.position,
                    rawOutput = e.transformedInput,
                    childConfig = e.config,
                )
                // run the child workflow
                tryCatch(node, taskStates) {
                    processChildWorkflowException(e, node, taskStates.toMap(), executionMode)
                }
            } catch (e: WaitWorkflowException) {
                if (executionMode.isAsync()) return WorkflowState.Waiting(
                    taskStates = taskStates.toMap(),
                    nodePosition = node.position,
                    rawOutput = e.transformedInput,
                    waitUntil = e.config.waitUntil,
                )
                // run the wait
                tryCatch(node, taskStates) {
                    processWaitException(e, node, taskStates.toMap())
                }
            } catch (e: ForkException) {
                // Fork needs to execute branches
                if (executionMode.isAsync()) {
                    // Async mode: Return state for runner to schedule branches
                    return WorkflowState.RunningFork(
                        taskStates = taskStates.toMap(),
                        nodePosition = node.position,
                        rawInput = e.transformedInput,
                        forkConfig = ForkConfig(
                            compete = e.config.compete,
                            branches = e.config.branches.map {
                                BranchExecution(
                                    index = it.index,
                                    name = it.name,
                                    nodePosition = it.nodePosition,
                                    status = BranchStatus.PENDING
                                )
                            },
                            completedBranches = emptyMap()
                        )
                    )
                }

                // Complete mode: Execute branches in parallel using coroutines
                // Then re-enter fork with assembled output
                tryCatch(node, taskStates) {
                    processForkException(e, node, taskStates.toMap(), executionMode)
                }
            }

            // Create new states map with updated state updates and context exports
            val newStates = taskStates.updateWith(result.stateUpdates, result.newContext)

            when (val retryAt = result.retryAt) {
                // Task completed
                null -> if (executionMode.stopAfterTaskCompletion(node) && result.nextNode != null)
                    return WorkflowState.ReadyForNextTask(
                        taskStates = newStates,
                        nodePosition = result.nextNode.position,
                        rawInput = result.rawInput,
                        flowDirective = result.flowDirective?.toKotlin(),
                    )
                // Task retried
                else -> {
                    if (executionMode.isAsync()) return WorkflowState.Retrying(
                        taskStates = newStates,
                        nodePosition = result.nextNode!!.position,
                        rawInput = result.rawInput,
                        flowDirective = result.flowDirective?.toKotlin(),
                        retryAt = retryAt
                    )
                    // wait before retry
                    executeDelay(retryAt, "Retrying at node: ${node.name} after")
                }
            }

            // Check if next node is a ForkTask - this means a branch just completed
            if (result.nextNode?.task is io.serverlessworkflow.api.types.ForkTask) {
                logger.debug { "Branch completed, returning to fork: ${result.nextNode.reference}" }

                // Return state indicating we're at the fork boundary
                return WorkflowState.ReadyForNextTask(
                    taskStates = newStates,
                    nodePosition = result.nextNode.position,
                    rawInput = result.rawInput,
                    flowDirective = result.flowDirective?.toKotlin()
                )
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
                taskStates = taskStates.toMap(),
                nodePosition = node.position,
                rawInput = rawInput,
                rawOutput = null,
                flowDirective = flowDirective?.toKotlin(),
                exception = e
            )
        }
    }

    internal suspend fun resumeFromInterruptedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        taskStates: TaskStates,
        executionMode: ExecutionMode
    ): WorkflowState = run {
        logger.debug { "resumeFromInterruptedTask In: node=${node.reference}, output=$rawOutput, states=$taskStates" }

        try {
            // Complete the interrupted task (transforms output, updates state)
            val result = tryCatch(node, taskStates) {
                completeInterruptedTask(node, rawOutput, taskStates)
            }

            // Create new states map with updated state updates and context exports
            val newStates = taskStates.updateWith(result.stateUpdates, result.newContext)

            // Continue execution from the next node (may pause again or complete)
            return@run resumeFromTask(
                taskStates = newStates,
                node = result.nextNode ?: return WorkflowState.Completed(result.rawInput),
                rawInput = result.rawInput,
                flowDirective = result.flowDirective,
                executionMode = executionMode
            )
        } catch (e: Exception) {
            return@run WorkflowState.Failed(
                taskStates = taskStates,
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
        taskStates: TaskStates,
        block: suspend () -> StepResult
    ): StepResult = try {
        block()
    } catch (e: InternalWorkflowException) {
        processInternalWorkflowException(e, current, taskStates)
    }

    private suspend fun processWaitException(
        exception: WaitWorkflowException,
        current: Node<*>,
        taskStates: TaskStates
    ): StepResult {
        // waiting
        executeDelay(exception.config.waitUntil, "Waiting at node: ${current.name} for")
        // complete the wait task
        return completeInterruptedTask(current, exception.transformedInput, taskStates)
    }

    /**
     * Processes starting a child workflow.
     */
    private suspend fun processChildWorkflowException(
        exception: ChildWorkflowException,
        current: Node<*>,
        taskStates: TaskStates,
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
        return completeInterruptedTask(current, childOutput, taskStates)
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
     * Process fork exception in Complete mode by executing branches in parallel.
     */
    private suspend fun processForkException(
        exception: ForkException,
        forkNode: Node<*>,
        taskStates: TaskStates,
        executionMode: ExecutionMode
    ): StepResult {
        logger.debug { "Executing fork branches in parallel: ${exception.config.branches.map { it.name }}" }

        // Get nodesMap from forkNode (we can navigate from any node to find nodesMap)
        // Build nodesMap from the fork node
        val nodesMap = mutableMapOf<NodePosition, Node<*>>()
        fun collect(node: Node<*>) {
            nodesMap[node.position] = node
            node.children?.forEach { collect(it) }
        }
        // Start from root (navigate up to root first)
        var root = forkNode
        while (root.parent != null) {
            root = root.parent
        }
        collect(root)

        // Create initial fork state if it doesn't exist (it won't on first entry)
        val forkState = taskStates[forkNode.position] as? ForkTaskState ?: ForkTaskState(
            startedAt = Clock.System.now(),
            branchStates = exception.config.branches.associate { it.index to BranchState.PENDING },
            branchOutputs = emptyMap()
        )
        val taskStatesWithFork = taskStates + (forkNode.position to forkState)

        // Execute branches and get results
        val branchResults = executeForkBranches(exception, taskStatesWithFork, executionMode, nodesMap)

        // Update fork state with completed branches
        val updatedTaskStates = updateForkStateWithResults(taskStatesWithFork, forkNode.position, branchResults)

        // Complete the fork task - this applies output transformation, export, etc.
        return completeInterruptedTask(forkNode, branchResults.assembledOutput, updatedTaskStates)
    }

    /**
     * Result of fork branch execution.
     */
    private data class ForkBranchResults(
        val outputs: Map<Int, JsonElement>,  // Branch index -> output
        val compete: Boolean,
        val assembledOutput: JsonElement
    )

    /**
     * Execute fork branches in parallel using coroutines.
     *
     * Branches execute concurrently. Each branch is a full workflow execution
     * that can contain any task types.
     *
     * In compete mode, returns as soon as first branch completes successfully.
     * In cooperative mode, waits for all branches to complete.
     */
    private suspend fun executeForkBranches(
        exception: ForkException,
        taskStates: TaskStates,
        executionMode: ExecutionMode,
        nodesMap: Map<NodePosition, Node<*>>
    ): ForkBranchResults {
        return if (exception.config.compete) {
            executeForkBranchesCompete(exception, taskStates, executionMode, nodesMap)
        } else {
            executeForkBranchesCooperative(exception, taskStates, executionMode, nodesMap)
        }
    }

    /**
     * Execute fork branches in compete mode (race for first completion).
     */
    private suspend fun executeForkBranchesCompete(
        exception: ForkException,
        taskStates: TaskStates,
        executionMode: ExecutionMode,
        nodesMap: Map<NodePosition, Node<*>>
    ): ForkBranchResults {
        val results = coroutineScope {
            exception.config.branches.map { branch ->
                async {
                    val output = executeSingleBranch(branch, taskStates, exception.transformedInput, executionMode, nodesMap)
                    branch.index to output
                }
            }
        }

        // Wait for first to complete
        val (index, output) = select {
            results.forEach { deferred ->
                deferred.onAwait { it }
            }
        }

        logger.debug { "Fork compete winner: index=$index" }

        return ForkBranchResults(
            outputs = mapOf(index to output),
            compete = true,
            assembledOutput = output
        )
    }

    /**
     * Execute fork branches in cooperative mode (wait for all).
     */
    private suspend fun executeForkBranchesCooperative(
        exception: ForkException,
        taskStates: TaskStates,
        executionMode: ExecutionMode,
        nodesMap: Map<NodePosition, Node<*>>
    ): ForkBranchResults {
        val outputs = coroutineScope {
            exception.config.branches.map { branch ->
                async {
                    executeSingleBranch(branch, taskStates, exception.transformedInput, executionMode, nodesMap)
                }
            }.awaitAll()
        }

        logger.debug { "Fork cooperative: all ${outputs.size} branches completed" }

        val outputMap = outputs.withIndex().associate { (index, output) -> index to output }

        return ForkBranchResults(
            outputs = outputMap,
            compete = false,
            assembledOutput = JsonArray(outputs)
        )
    }

    /**
     * Execute a single fork branch.
     */
    private suspend fun executeSingleBranch(
        branch: ForkException.BranchInfo,
        taskStates: TaskStates,
        branchInput: JsonElement,
        executionMode: ExecutionMode,
        nodesMap: Map<NodePosition, Node<*>>
    ): JsonElement {
        val branchNode = nodesMap[branch.nodePosition]
            ?: throw IllegalStateException("Branch node not found at ${branch.nodePosition}")

        logger.debug { "Executing branch: ${branch.name}" }

        val result = resumeFromTask(
            taskStates = taskStates,
            node = branchNode,
            rawInput = branchInput,
            flowDirective = null,
            executionMode = executionMode
        )

        return extractBranchOutput(result, branch.name)
    }

    /**
     * Extract output from a completed branch based on its final state.
     */
    private fun extractBranchOutput(result: WorkflowState, branchName: String): JsonElement {
        return when (result) {
            is WorkflowState.ReadyForNextTask -> {
                logger.debug { "Branch $branchName completed, stopped at fork" }
                result.rawInput
            }

            is WorkflowState.Completed -> {
                logger.debug { "Branch $branchName completed entirely" }
                result.output
            }

            is WorkflowState.Failed -> {
                logger.error { "Branch $branchName failed: ${result.error}" }
                throw result.exception ?: InternalWorkflowException(result.error)
            }

            else -> {
                throw IllegalStateException(
                    "Branch execution returned unexpected state: ${result::class.simpleName} for branch $branchName"
                )
            }
        }
    }

    /**
     * Update fork state with branch results.
     */
    private fun updateForkStateWithResults(
        taskStates: TaskStates,
        forkPosition: NodePosition,
        results: ForkBranchResults
    ): TaskStates {
        val currentForkState = taskStates[forkPosition] as? ForkTaskState
            ?: throw IllegalStateException("Fork state not found at $forkPosition")

        val updatedForkState = currentForkState.copy(
            branchStates = currentForkState.branchStates.mapValues { (index, _) ->
                if (index in results.outputs.keys) BranchState.COMPLETED else BranchState.PENDING
            },
            branchOutputs = results.outputs
        )

        return taskStates + (forkPosition to updatedForkState)
    }

    /**
     * Resume workflow from RunningFork state.
     *
     * This is called when:
     * 1. Runner has scheduled and executed branches
     * 2. One or more branches have completed
     * 3. Fork needs to check completion status
     *
     * @param workflow Workflow definition
     * @param state Current RunningFork state
     * @param executionMode Execution mode
     * @return Updated workflow state
     */
    private suspend fun resumeFromFork(
        workflow: Workflow,
        state: WorkflowState.RunningFork,
        executionMode: ExecutionMode
    ): WorkflowState {
        val nodesMap = DefinitionCache.getNodesMap(workflow)
        val forkNode = nodesMap[state.nodePosition]
            ?: throw IllegalStateException("Fork node not found at ${state.nodePosition}")

        logger.debug { "Resuming fork: ${state.forkConfig.branches.size} branches, ${state.forkConfig.completedBranches.size} completed" }

        // Check if fork is complete
        val isComplete = when {
            state.forkConfig.compete && state.forkConfig.completedBranches.isNotEmpty() -> true
            !state.forkConfig.compete && state.forkConfig.completedBranches.size == state.forkConfig.branches.size -> true
            else -> false
        }

        if (isComplete) {
            // Fork complete - assemble output and continue parent workflow
            val output = assembleForkOutput(state.forkConfig)

            // Complete the fork task (applies output transformation, export, etc.)
            val result = completeInterruptedTask(
                node = forkNode,
                rawOutput = output,
                taskStates = state.taskStates
            )

            return resumeFromTask(
                taskStates = state.taskStates.updateWith(result.stateUpdates, result.newContext),
                node = result.nextNode ?: return WorkflowState.Completed(result.rawInput),
                rawInput = result.rawInput,
                flowDirective = result.flowDirective,
                executionMode = executionMode
            )
        }

        // Fork not complete - return state for runner to continue
        return state
    }

    /**
     * Assemble final output from completed branches.
     */
    private fun assembleForkOutput(config: ForkConfig): JsonElement {
        return if (config.compete) {
            // Compete mode: return first completed branch output
            config.completedBranches.values.first()
        } else {
            // Cooperative mode: return array in declaration order
            JsonArray(
                config.branches.indices.map { index ->
                    config.completedBranches[index]
                        ?: throw IllegalStateException("Branch $index not completed")
                }
            )
        }
    }

    /**
     * Handles a delay operation
     */
    suspend fun executeDelay(waitUntil: Instant, logMessage: String) {
        val delayDuration = waitUntil - Clock.System.now()
        logger.debug { "$logMessage: $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
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
     * @param taskStates Current states map
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with: next node, dataset, deltaStates, and flow directive
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun runStep(
        node: Node<*>,
        dataset: JsonElement,
        taskStates: TaskStates,
        flowDirective: FlowDirective?,
    ): StepResult {
        val state = taskStates[node.position]
        val scope = getScope(node, taskStates)
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
    ): NodeProcessor<T, TaskState> {
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
            is ForkTask -> ForkProcessor(node as Node<ForkTask>)
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
        } as NodeProcessor<T, TaskState>
    }

    /**
     * Retrieves the `Scope` associated with the given node by combining its own expression arguments
     * with those of its parent nodes in the tree, if present.
     */
    private fun getScope(current: Node<*>, taskStates: TaskStates): Scope =
        (taskStates[current.position]?.scope ?: buildJsonObject { })
            // Recursively merge with parent scope
            .merge(current.parent?.let { getScope(it, taskStates) })

    /**
     * Handle exception by finding a TryTask and returning the appropriate state transition.
     */
    private fun processInternalWorkflowException(
        exception: InternalWorkflowException,
        failingNode: Node<*>,
        taskStates: TaskStates,
    ): StepResult {
        // Find the nearest TryTask that can handle this error
        var tryNode: Node<*>? = failingNode

        while (tryNode != null) {
            if (tryNode.task is TryTask) {
                @Suppress("UNCHECKED_CAST")
                tryNode as Node<TryTask>
                // current scope of the try node
                val tryScope = getScope(tryNode, taskStates)
                // current state of the try node
                val tryTaskState = taskStates[tryNode.position] as TryTaskState
                // build a processor for the try node
                val processor = getNodeProcessor(tryNode) as TryProcessor
                // check that this node actually can handle this error
                if (processor.isCatching(exception.error, tryTaskState, tryScope)) {
                    return processor.handleError(
                        failingNode = failingNode,
                        error = exception.error,
                        state = tryTaskState,
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
     * @param taskStates The current workflow states
     * @return StepResult containing the next node, dataset, and state updates
     */
    private fun completeInterruptedTask(
        node: Node<*>,
        rawOutput: JsonElement,
        taskStates: TaskStates
    ): StepResult {
        val processor = getNodeProcessor(node)
        val scope = getScope(node, taskStates)

        return processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = processor.getFlowDirective(),
            parentScope = scope,
            taskContext = null
        )
    }

}
