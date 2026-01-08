// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RunState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.RunWorkflowStarted
import io.serverlessworkflow.api.types.RunTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
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

    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = RunState()

    /**
     * Prepares and handles the event for starting a sub-workflow.
     *
     * This method processes a sub-workflow's configuration, input transformation,
     * and execution behavior (such as whether it awaits completion). It encapsulates
     * these details into a `WorkflowEvent`, which is returned to signify the start of the sub-workflow.
     *
     * @param nodeStack The current stack representing the workflow's task state hierarchy.
     * @param transformedInput The transformed input data for the current workflow node.
     * @param scope The execution context and variable bindings available to this workflow step.
     * @param state The current call state representing runtime and execution metadata for the node.
     * @return A `WorkflowEvent` instance representing the start of a sub-workflow.
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
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

        val childWorkflowConfig = RunWorkflowConfig(
            namespace = subWorkflowNamespace,
            name = subWorkflowName,
            version = subWorkflowVersion,
            input = childWorkflowInput,
            sync = awaitCompletion
        )

        // returns RunWorkflowStarted event for an asynchronous execution
        return RunWorkflowStarted(
            nodeStack = nodeStack,
            rawInput = transformedInput,
            config = childWorkflowConfig,
        )
    }
}

/**
 * Configuration details required to initiate a child workflow.
 *
 * This data class encapsulates the metadata and input parameters needed to
 * start a child workflow instance within a larger workflow process.
 *
 * @property namespace The namespace of the child workflow, used to scope workflows within an environment.
 * @property name The name of the child workflow to be executed.
 * @property version The version of the child workflow.
 * @property input The input provided to the child workflow.
 * @property sync Indicates whether the parent workflow should wait for the child workflow to complete.
 */
@Serializable
data class RunWorkflowConfig(
    val namespace: WorkflowNamespace,
    val name: WorkflowName,
    val version: WorkflowVersion,
    val input: JsonElement,
    val sync: Boolean
)
