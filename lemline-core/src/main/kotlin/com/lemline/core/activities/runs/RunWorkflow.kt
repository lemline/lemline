// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.ids.IdGenerator
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowException
import com.lemline.core.instances.RunInstance
import com.lemline.core.processor.Processor
import io.serverlessworkflow.api.types.RunWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal suspend fun RunInstance.runWorkflow(runWorkflow: RunWorkflow): JsonElement {
    logDebug { "Executing run workflow command: ${node.name}" }

    val subWorkflowName = runWorkflow.workflow.name
    val subWorkflowVersion = runWorkflow.workflow.version

    logDebug { "Sub-workflow name: $subWorkflowName, version: $subWorkflowVersion" }

    // Determine the input for the sub-workflow by evaluating the 'input' expression if it exists
    val childWorkflowInput = runWorkflow.getInputFor(this)

    logDebug { "Sub-workflow input data: $childWorkflowInput" }

    val awaitCompletion = runWorkflow.isAwait
    logDebug { "Await sub-workflow completion: $awaitCompletion" }

    // Create the sub-workflow instance. It will be used in both await and non-await cases.
    val subProcessor: Processor = Processor.createNew(
        name = subWorkflowName,
        version = subWorkflowVersion,
        id = IdGenerator.generateV7(),
        rawInput = childWorkflowInput,
        secrets = rootInstance.secrets,
        activityRunnerProvider = processor.activityRunnerProvider,
    )

    if (!awaitCompletion) {
        // For non-awaiting execution, we launch the workflow in a separate coroutine.
        Processor.scope.launch {
            try {
                subProcessor.run()
            } catch (e: Exception) {
                // It's important to log errors from async workflows.
                logError(e) { "Asynchronous sub-workflow ${subProcessor.instance.id} failed." }
            }
        }
        logInfo { "Launched sub-workflow ${subProcessor.instance.id} asynchronously." }
        // As per DSL, output for await: false is the transformed input
        return transformedInput
    }

    // For awaiting execution, run the sub-workflow and handle its result or exception.
    logInfo { "Starting sub-workflow instance ${subProcessor.instance.id} and awaiting completion." }

    try {
        // The run() method now returns the result directly on success.
        val subWorkflowResult = subProcessor.run()
        logInfo { "Sub-workflow ${subProcessor.instance.id} finished successfully." }
        return subWorkflowResult
    } catch (e: WorkflowException) {
        // If run() throws an exception, the sub-workflow has faulted.
        logError(e) { "Sub-workflow ${subProcessor.instance.id} faulted." }
        // Propagate the error to the parent workflow.
        raiseError(
            WorkflowErrorType.RUNTIME,
            "Sub-workflow execution failed: ${e.error.type}",
            e.error.details,
            e.error.status
        )
    }
}

/**
 * Returns the input for the provided run instance.
 *
 * @param runInstance The run instance containing the input configuration that will be evaluated.
 */
fun RunWorkflow.getInputFor(runInstance: RunInstance) = runInstance.eval(runInstance.transformedInput, workflow.input)
