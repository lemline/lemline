// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.core.errors.RunWorkflowException
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunWorkflow
import kotlinx.serialization.json.JsonElement

internal fun NodeInstance<*>.run(runWorkflow: RunWorkflow): JsonElement {
    logInfo { "Executing run workflow command: ${node.name}" }

    logDebug { "Workflow name: ${runWorkflow.workflow.name}" }
    logDebug { "Workflow version: ${runWorkflow.workflow.version}" }
    logDebug { "Workflow input: ${runWorkflow.workflow.input}" }

    throw RunWorkflowException()
}
