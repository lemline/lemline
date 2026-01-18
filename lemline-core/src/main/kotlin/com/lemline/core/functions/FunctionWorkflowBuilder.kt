// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.functions

import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.workflows.WorkflowCache
import io.serverlessworkflow.api.types.Document
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.Use
import io.serverlessworkflow.api.types.UseFunctions
import io.serverlessworkflow.api.types.Workflow

/**
 * Builds synthetic workflows for function execution.
 *
 * When a function is called, its task needs to be executed through the
 * orchestrator. This builder wraps the task in a minimal workflow that
 * can be executed via [com.lemline.core.orchestrator.FullOrchestrator].
 *
 * ## Caching Strategy
 *
 * - **Remote functions** (URLs, catalog refs): Cached globally since they're
 *   the same across all workflows.
 * - **Named functions**: Cached per parent workflow to avoid collisions when
 *   different workflows have functions with the same name.
 *
 * ## Namespace
 *
 * All synthetic workflows use the `_function` namespace to distinguish
 * them from user-defined workflows.
 */
object FunctionWorkflowBuilder {

    /** Namespace for all synthetic function workflows */
    private val FUNCTION_NAMESPACE = WorkflowNamespace("_function")

    /** Version for synthetic workflows */
    private val FUNCTION_VERSION = WorkflowVersion("1.0.0")

    /**
     * Builds (or retrieves from cache) a synthetic workflow for a function.
     *
     * The workflow wraps the function's task in a minimal structure:
     * ```yaml
     * document:
     *   dsl: '1.0.0'
     *   namespace: _function
     *   name: <functionRef>  # or <parentInfo>/<functionRef> for named functions
     *   version: '1.0.0'
     * use:
     *   functions:
     *     <parent's functions>  # enables nested function calls
     * do:
     *   - functionTask:
     *       <task definition>
     * ```
     *
     * @param task The [Task] to wrap in a workflow
     * @param functionRef The function reference (used as workflow name and cache key)
     * @param parentWorkflowInfo The parent workflow info (for named function scoping, null for remote)
     * @param parentUseFunctions The parent workflow's functions (for nested function calls)
     * @return The synthetic workflow (from cache if available)
     */
    fun build(
        task: Task,
        functionRef: String,
        parentWorkflowInfo: WorkflowInfo? = null,
        parentUseFunctions: Map<String, Task>? = null,
    ): Workflow {
        val functionNamespace = parentWorkflowInfo?.namespace ?: FUNCTION_NAMESPACE
        val functionName = WorkflowName((parentWorkflowInfo?.name?.let { "$it?fun=" } ?: "") + functionRef)
        val functionVersion = parentWorkflowInfo?.version ?: FUNCTION_VERSION

        // Check cache
        WorkflowCache.getWorkflow(functionNamespace, functionName, functionVersion)?.let { return it }

        // Build synthetic workflow programmatically
        val workflow = Workflow().apply {
            document = Document().apply {
                dsl = "1.0.0"
                namespace = functionNamespace.toString()
                name = functionName.toString()
                version = functionVersion.toString()
            }
            // Include parent's functions for nested function calls
            if (!parentUseFunctions.isNullOrEmpty()) {
                use = Use().apply {
                    functions = UseFunctions().apply {
                        parentUseFunctions.forEach { (name, funcTask) ->
                            setAdditionalProperty(name, funcTask)
                        }
                    }
                }
            }
            `do` = listOf(
                TaskItem("functionTask", task)
            )
        }
        // Cache and return
        WorkflowCache.putWorkflow(workflow)

        return workflow
    }
}
