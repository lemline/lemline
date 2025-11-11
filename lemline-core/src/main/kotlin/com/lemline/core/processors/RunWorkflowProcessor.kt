// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.execution.ExecutionOrchestrator
import com.lemline.core.execution.context.Scope
import com.lemline.core.states.NoState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
) : NodeProcessor<RunTask, NoState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): NoState = NoState()

    /**
     * Execute sub-workflow action.
     *
     * Executes a sub-workflow with the configured parameters and returns the result.
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return Sub-workflow execution result as JsonElement
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement {
        logger.debug { "Executing run workflow: ${node.name}" }

        // Extract workflow configuration
        val runConfig = node.task.run.get()
        if (runConfig !is RunWorkflow) {
            raiseError(
                WorkflowErrorType.RUNTIME,
                "Expected RunWorkflow configuration but got: ${runConfig?.javaClass?.simpleName}"
            )
        }

        val workflowConfig = runConfig.workflow

        // Extract namespace, name, and version
        val subWorkflowNamespace = WorkflowNamespace(workflowConfig.namespace)
        val subWorkflowName = WorkflowName(workflowConfig.name)
        val subWorkflowVersion = WorkflowVersion(workflowConfig.version)

        logger.debug { "Sub-workflow: namespace=$subWorkflowNamespace, name=$subWorkflowName, version=$subWorkflowVersion" }

        // Get the workflow definition from cache
        val subWorkflow = DefinitionCache.getOrNull(subWorkflowNamespace, subWorkflowName, subWorkflowVersion)
            ?: raiseError(
                WorkflowErrorType.CONFIGURATION,
                "Sub-workflow not found: namespace=$subWorkflowNamespace, name=$subWorkflowName, version=$subWorkflowVersion"
            )

        // Get the root node of the sub-workflow
        val subWorkflowRootNode = DefinitionCache.getRootNode(subWorkflow)

        // Determine the input for the sub-workflow by evaluating the 'input' expression if it exists
        val childWorkflowInput = eval(transformedInput, workflowConfig.input, scope)

        logger.debug { "Sub-workflow input: $childWorkflowInput" }

        val awaitCompletion = runConfig.isAwait
        logger.debug { "Await sub-workflow completion: $awaitCompletion" }

        if (!awaitCompletion) {
            // For non-awaiting execution, launch the workflow asynchronously and return immediately
            // Note: In production (lemline-runner), this is handled via the outbox pattern with
            // proper persistence and parent-child relationship tracking
            logger.debug { "Launching sub-workflow asynchronously (fire-and-forget)" }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ExecutionOrchestrator.run(subWorkflowRootNode, childWorkflowInput)
                    logger.debug { "Async sub-workflow execution completed successfully" }
                } catch (e: Exception) {
                    logger.error(e) { "Async sub-workflow execution failed" }
                }
            }
            return transformedInput
        }

        // For awaiting execution, run the sub-workflow and return its result
        logger.debug { "Starting sub-workflow execution and awaiting completion" }

        return try {
            val subWorkflowResult = ExecutionOrchestrator.run(subWorkflowRootNode, childWorkflowInput)
            logger.debug { "Sub-workflow execution completed successfully" }
            subWorkflowResult
        } catch (e: Exception) {
            logger.error(e) { "Sub-workflow execution failed" }
            val errorMsg = "Sub-workflow execution failed: ${e.message}"
            raiseError(WorkflowErrorType.RUNTIME, errorMsg, e.stackTraceToString())
        }
    }
}
