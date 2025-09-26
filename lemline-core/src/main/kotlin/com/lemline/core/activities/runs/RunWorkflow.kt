// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
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
    logger.debug { "Executing run workflow command: ${node.name}" }

    val subWorkflowNamespace = WorkflowNamespace(runWorkflow.workflow.namespace)
    val subWorkflowName = WorkflowName(runWorkflow.workflow.name)
    val subWorkflowVersion = WorkflowVersion(runWorkflow.workflow.version)

    logger.debug { "Sub-workflow name: $subWorkflowName, version: $subWorkflowVersion" }

    // Determine the input for the sub-workflow by evaluating the 'input' expression if it exists
    val childWorkflowInput = runWorkflow.getInputFor(this)

    logger.debug { "Sub-workflow input data: $childWorkflowInput" }

    val awaitCompletion = runWorkflow.isAwait
    logger.debug { "Await sub-workflow completion: $awaitCompletion" }

    // Create the sub-workflow instance. It will be used in both await and non-await cases.
    val subProcessor: Processor = Processor.createNew(
        namespace = subWorkflowNamespace,
        name = subWorkflowName,
        version = subWorkflowVersion,
        id = WorkflowId.random(),
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
                logger.error(e) { "Asynchronous sub-workflow ${subProcessor.workflowId} failed." }
            }
        }
        logger.debug { "Launched sub-workflow ${subProcessor.workflowId} asynchronously." }
        // As per DSL, output for await: false is the transformed input
        return transformedInput
    }

    // For awaiting execution, run the sub-workflow and handle its result or exception.
    logger.debug { "Starting sub-workflow instance ${subProcessor.workflowId} and awaiting completion." }

    try {
        // The run() method now returns the result directly on success.
        val subWorkflowResult = subProcessor.run()
        logger.debug { "Sub-workflow ${subProcessor.workflowId} finished successfully." }
        return subWorkflowResult
    } catch (e: WorkflowException) {
        // If run() throws an exception, the sub-workflow has faulted.
        logger.warn(e) { "Sub-workflow ${subProcessor.workflowId} faulted." }
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
