// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowException
import com.lemline.core.instances.RunInstance
import com.lemline.core.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.RunWorkflow
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

internal suspend fun RunInstance.runWorkflow(runWorkflow: RunWorkflow): JsonElement {
    logInfo { "Executing run workflow command: ${node.name}" }

    val subWorkflowDef = runWorkflow.workflow
    val subWorkflowName = subWorkflowDef.name
    val subWorkflowVersion = subWorkflowDef.version

    logDebug { "Sub-workflow name: $subWorkflowName, version: $subWorkflowVersion" }

    // Determine the input for the sub-workflow by evaluating the 'input' expression if it exists
    val subWorkflowInput = eval(transformedInput, subWorkflowDef.input)
    
    logDebug { "Sub-workflow input data: $subWorkflowInput" }

    val awaitCompletion = runWorkflow.isAwait
    logDebug { "Await sub-workflow completion: $awaitCompletion" }

    // Create the sub-workflow instance. It will be used in both await and non-await cases.
    val subWorkflowInstance = WorkflowInstance.createNew(
        name = subWorkflowName,
        version = subWorkflowVersion,
        id = UUID.randomUUID().toString(),
        rawInput = subWorkflowInput,
        secrets = rootInstance.secrets,
        activityRunnerProvider = rootInstance.activityRunnerProvider,
    )

    if (!awaitCompletion) {
        // For non-awaiting execution, we launch the workflow in a separate coroutine.
        WorkflowInstance.scope.launch {
            try {
                subWorkflowInstance.run()
            } catch (e: Exception) {
                // It's important to log errors from async workflows.
                logError(e) { "Asynchronous sub-workflow ${subWorkflowInstance.id} failed." }
            }
        }
        logInfo { "Launched sub-workflow ${subWorkflowInstance.id} asynchronously." }
        // As per DSL, output for await: false is the transformed input
        return transformedInput
    }

    // For awaiting execution, run the sub-workflow and handle its result or exception.
    logInfo { "Starting sub-workflow instance ${subWorkflowInstance.id} and awaiting completion." }

    try {
        // The run() method now returns the result directly on success.
        val subWorkflowResult = subWorkflowInstance.run()
        logInfo { "Sub-workflow ${subWorkflowInstance.id} finished successfully." }
        return subWorkflowResult
    } catch (e: WorkflowException) {
        // If run() throws an exception, the sub-workflow has faulted.
        logError(e) { "Sub-workflow ${subWorkflowInstance.id} faulted." }
        // Propagate the error to the parent workflow.
        onError(
            WorkflowErrorType.RUNTIME,
            "Sub-workflow execution failed: ${e.error.type}",
            e.error.details,
            e.error.status
        )
    }
}

