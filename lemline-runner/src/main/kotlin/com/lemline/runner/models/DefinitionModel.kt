// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

data class DefinitionModel(
    val namespace: WorkflowNamespace,

    val name: WorkflowName,

    val version: WorkflowVersion,

    val definition: String
) {
    companion object {
        fun from(workflow: Workflow) = DefinitionModel(
            namespace = WorkflowNamespace(workflow.document.namespace),
            name = WorkflowName(workflow.document.name),
            version = WorkflowVersion(workflow.document.version),
            definition = LemlineJson.yamlMapper.writeValueAsString(workflow)
        )
    }
}
