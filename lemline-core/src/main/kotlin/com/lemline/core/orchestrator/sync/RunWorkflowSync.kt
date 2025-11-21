// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.sync

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.RunWorkflowStartedException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.TaskStates
import com.lemline.core.states.WorkflowEvent
import io.serverlessworkflow.api.types.Workflow
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
        config: RunWorkflowStartedException.Config,
        executionMode: ExecutionMode
    ): StepResult {
        // In case we change execution modes in the future, we check it here
        if (executionMode != ExecutionMode.CONTINUOUS) {
            throw IllegalStateException("Invalid execution mode: $executionMode")
        }

        // Resolve the sub-workflow definition from the cache
        val childWorkflow = resolveWorkflow(config)

        // Execute the sub-workflow synchronously or asynchronously
        val childOutput = when (config.sync) {
            true -> executeSync(childWorkflow, config.input)
            false -> {
                executeAsync(childWorkflow, config.input)
                transformedInput!!
            }
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
    fun resolveWorkflow(config: RunWorkflowStartedException.Config): Workflow {
        val childWorkflowName by lazy {
            "(namespace=${config.namespace}, name=${config.name}, version=${config.version})"
        }

        return DefinitionCache.getWorkflow(
            namespace = config.namespace,
            name = config.name,
            version = config.version
        ) ?: throw IllegalStateException(
            "Workflow definition not found for sub-workflow: $childWorkflowName"
        )
    }

    /**
     * Executes a child workflow synchronously and returns its output as a JSON element.
     *
     * @param workflow The workflow to execute.
     * @param workflowInput The input data for the workflow as a JSON element.
     * @return The output of the workflow execution as a JSON element.
     * @throws IllegalStateException If the workflow execution results in an unexpected output type.
     * @throws Exception If the child workflow fails and propagates its failure.
     */
    suspend fun executeSync(
        workflow: Workflow,
        workflowInput: JsonElement,
    ): JsonElement {
        logger.debug { "Executing child workflow inline: ${workflow.document.name}" }

        return when (val output = WorkflowOrchestrator.start(
            workflow = workflow,
            workflowInput = workflowInput,
            hasWaitingParent = true,
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
                "Unexpected output type: ${output::class.simpleName} for child workflow  ${workflow.document.name}"
            )
        }
    }

    /**
     * Executes a child workflow asynchronously in a fire-and-forget manner. The method logs the execution
     * status, capturing whether the workflow's execution completes successfully or fails to run.
     * This is meant to launch another workflow process without waiting for its completion.
     *
     * @param workflow The workflow to be executed asynchronously.
     * @param workflowInput The input data passed to the workflow as a JSON element.
     */
    suspend fun executeAsync(
        workflow: Workflow,
        workflowInput: JsonElement,
    ) {
        logger.debug { "Launching child workflow asynchronously (fire-and-forget): ${workflow.document.name}" }

        CoroutineScope(currentCoroutineContext()).launch {
            runCatching {
                WorkflowOrchestrator.start(
                    workflow = workflow,
                    workflowInput = workflowInput,
                    hasWaitingParent = false,
                    executionMode = ExecutionMode.CONTINUOUS
                )
            }.onSuccess {
                logger.debug { "Async child workflow completed successfully" }
            }.onFailure { ex ->
                logger.error(ex) { "Async child workflow failed" }
            }
        }
    }
}
