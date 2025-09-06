// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

data class DefinitionModel(
    val name: WorkflowName,

    val version: WorkflowVersion,

    val definition: String
) {
    companion object {
        fun from(workflow: Workflow) = DefinitionModel(
            name = WorkflowName(workflow.document.name),
            version = WorkflowVersion(workflow.document.version),
            definition = LemlineJson.yamlMapper.writeValueAsString(workflow)
        )
    }
}
