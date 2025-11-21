// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.RunWorkflowStartedException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.RunState
import io.serverlessworkflow.api.types.RunTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for RunTask with Workflow configuration - pure functional model.
 *
 * RunWorkflow enables workflows to execute other workflows (sub-workflows), supporting
 * modularization and logic reusability. It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - callSubWorkflow:
 *       run:
 *         workflow:
 *           namespace: myapp
 *           name: data-processor
 *           version: '1.0.0'
 *           input:
 *             data: ${ .inputData }
 *   - callRecursive:
 *       run:
 *         workflow:
 *           namespace: test
 *           name: factorial
 *           version: '0.1.0'
 *           input:
 *             n: ${ .n - 1 }
 * ```
 *
 * ## Workflow Configuration
 *
 * The `workflow` field contains workflow-specific arguments:
 * - **namespace**: Target workflow's namespace (optional, defaults to current namespace)
 * - **name**: The name of the workflow to run (required)
 * - **version**: Workflow version (required, can be 'latest')
 * - **input**: The data to pass as input to the workflow (optional)
 * - **await**: Whether to wait for workflow completion (defaults to true)
 *
 * @property node Immutable RunTask definition with workflow configuration
 */
class RunWorkflowProcessor(
    node: Node<RunTask>,
) : NodeProcessor<RunTask, RunState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope) = RunState()

    /**
     * Execute sub-workflow action.
     *
     * This method transforms the input and throws ChildWorkflowStartedException to signal
     * that a child workflow should be started. The orchestrator catches this exception,
     * resolves the workflow definition from the cache, and handles execution appropriately
     * (CompleteOrchestrator vs PausableOrchestrator).
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return This method always throws ChildWorkflowStartedException
     * @throws RunWorkflowStartedException Always thrown to signal child workflow initiation
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
        state: RunState,
    ): JsonElement {
        logger.debug { "Preparing sub-workflow: ${node.name}" }

        // Extract workflow configuration
        val runWorkflow = node.task.run.runWorkflow
        val workflowConfig = runWorkflow.workflow

        // Extract namespace, name, and version
        val subWorkflowNamespace = WorkflowNamespace(workflowConfig.namespace)
        val subWorkflowName = WorkflowName(workflowConfig.name)
        val subWorkflowVersion = WorkflowVersion(workflowConfig.version)

        // Determine the input for the sub-workflow by evaluating the 'input' expression if it exists
        val childWorkflowInput = runWorkflowInput(transformedInput, workflowConfig.input, scope)

        val awaitCompletion = runWorkflow.isAwait

        val childWorkflowConfig = RunWorkflowStartedException.Config(
            namespace = subWorkflowNamespace,
            name = subWorkflowName,
            version = subWorkflowVersion,
            input = childWorkflowInput,
            sync = awaitCompletion
        )

        // The orchestrator will resolve the definition and handle execution appropriately
        logger.debug { "Throwing ${RunWorkflowStartedException::class.simpleName} for orchestrator to handle:  $childWorkflowConfig" }
        throw RunWorkflowStartedException(
            state = state,
            transformedInput = transformedInput,
            config = childWorkflowConfig
        )
    }
}
