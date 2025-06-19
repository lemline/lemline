// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities.runners

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunWorkflow

internal fun NodeInstance<*>.run(runWorkflow: RunWorkflow) {
    logInfo { "Executing run workflow command: ${node.name}" }

    logDebug { "Workflow name: ${runWorkflow.workflow.name}" }
    logDebug { "Workflow version: ${runWorkflow.workflow.version}" }
    logDebug { "Workflow input: ${runWorkflow.workflow.input}" }

    try {

    } catch (e: Exception) {
        logError(e) { "Failed to execute shell command" }
        val errorMsg = "Shell command execution failed: ${e.message}"
        onError(COMMUNICATION, errorMsg)
    }
}
