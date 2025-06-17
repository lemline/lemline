// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities.runners

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunWorkflow

internal fun NodeInstance<*>.run(runWorkflow: RunWorkflow) {
    info { "Executing run workflow command: ${node.name}" }

    debug { "Workflow name: ${runWorkflow.workflow.name}" }
    debug { "Workflow version: ${runWorkflow.workflow.version}" }
    debug { "Workflow input: ${runWorkflow.workflow.input}" }

    try {

    } catch (e: Exception) {
        error(e) { "Failed to execute shell command" }
        val errorMsg = "Shell command execution failed: ${e.message}"
        error(COMMUNICATION, errorMsg)
    }
}
