// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.sync

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.RunWorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.TaskStates
import com.lemline.core.states.WorkflowEvent
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Executes RunWorkflow tasks (child/sub-workflows).
 *
 * This executor manages both synchronous (await=true) and asynchronous (await=false)
 * child workflow execution modes, including workflow resolution and state management.
 */
@ExperimentalTime
internal object RunWorkflowSync {

    private val logger = logger()

    /**
     * Processes a child workflow execution request.
     *
     * @param taskStates Current workflow task states
     * @param runWorkflowNode The node containing the RunWorkflow task
     * @param transformedInput The input data for the child workflow
     * @param config Configuration for the child workflow execution
     * @param executionMode The execution mode (must be CONTINUOUS for sync execution)
     * @return StepResult after completing the child workflow task
     */
    suspend fun execute(
        taskStates: TaskStates,
        runWorkflowNode: Node<*>,
        transformedInput: JsonElement?,
        config: RunWorkflowException.Config,
        executionMode: ExecutionMode
    ): StepResult {
        // In case we change execution modes in the future, we check it here
        if (executionMode != ExecutionMode.CONTINUOUS) {
            throw IllegalStateException("Invalid execution mode: $executionMode")
        }

        // Resolve the sub-workflow definition from the cache
        val subRootNode = resolveRootNode(config)

        // Execute the sub-workflow synchronously or asynchronously
        val childOutput = when (config.sync) {
            true -> executeSync(config, subRootNode)
            false -> executeAsync(transformedInput, config, subRootNode)
        }

        // Complete the child workflow task
        return WorkflowOrchestrator.completeStartedTask(taskStates, runWorkflowNode, childOutput)
    }

    /**
     * Resolves the root node of a sub-workflow from the definition cache.
     *
     * @param config Configuration containing workflow coordinates (namespace, name, version)
     * @return The root node of the resolved workflow
     * @throws IllegalStateException if the workflow definition is not found
     */
    fun resolveRootNode(config: RunWorkflowException.Config): Node<*> {
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
     * Executes a child workflow synchronously (await=true).
     *
     * The parent workflow waits for the child to complete and receives its output.
     *
     * @param config Child workflow configuration
     * @param subWorkflowRootNode Root node of the child workflow
     * @return The output from the completed child workflow
     * @throws Exception if the child workflow fails
     */
    suspend fun executeSync(
        config: RunWorkflowException.Config,
        subWorkflowRootNode: Node<*>,
    ): JsonElement {
        logger.debug { "Executing child workflow inline: ${config.name}" }

        return when (val output = WorkflowOrchestrator.resumeFromTask(
            node = subWorkflowRootNode,
            rawInput = config.input,
            executionMode = ExecutionMode.CONTINUOUS
        )) {
            is WorkflowEvent.WorkflowCompleted -> {
                logger.debug { "Child workflow completed, continuing parent" }
                output.output
            }

            is WorkflowEvent.TaskFailed -> {
                logger.debug { "Child workflow failed, continuing parent" }
                throw output.exception
            }

            else -> throw IllegalStateException(
                "Unexpected output type: ${output::class.simpleName} for child workflow $config"
            )
        }
    }

    /**
     * Executes a child workflow asynchronously (await=false, fire-and-forget).
     *
     * The parent workflow continues immediately without waiting for child completion.
     *
     * @param transformedInput The input to return to the parent (child runs independently)
     * @param config Child workflow configuration
     * @param subWorkflowRootNode Root node of the child workflow
     * @return The transformed input (parent continues immediately)
     */
    suspend fun executeAsync(
        transformedInput: JsonElement?,
        config: RunWorkflowException.Config,
        subWorkflowRootNode: Node<*>,
    ): JsonElement {
        logger.debug { "Launching child workflow asynchronously (fire-and-forget): ${config.name}" }

        CoroutineScope(currentCoroutineContext()).launch {
            runCatching {
                WorkflowOrchestrator.resumeFromTask(
                    node = subWorkflowRootNode,
                    rawInput = config.input,
                    executionMode = ExecutionMode.CONTINUOUS
                )
            }.onSuccess {
                logger.debug { "Async child workflow completed successfully" }
            }.onFailure { ex ->
                logger.error(ex) { "Async child workflow failed" }
            }
        }

        return transformedInput!! // Use current input for fire-and-forget
    }
}
