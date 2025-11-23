// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

data class DefinitionModel(
    /** The namespace of the workflow definition */
    val namespace: WorkflowNamespace,

    /** The name of the workflow definition */
    val name: WorkflowName,

    /** The version of the workflow definition */
    val version: WorkflowVersion,

    /** The complete workflow definition in YAML format */
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
