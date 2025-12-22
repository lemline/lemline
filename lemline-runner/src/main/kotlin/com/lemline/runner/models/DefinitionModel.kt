// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.workflows.WorkflowCache
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
        /**
         * Creates a DefinitionModel from a Workflow object.
         * The definition is re-serialized to YAML.
         */
        fun from(workflow: Workflow) = DefinitionModel(
            namespace = workflow.namespace,
            name = workflow.name,
            version = workflow.version,
            definition = LemlineJson.yamlMapper.writeValueAsString(workflow)
        )

        /**
         * Creates a DefinitionModel from a raw definition string (YAML or JSON).
         * The original content is preserved as-is.
         */
        fun from(definition: String): DefinitionModel {
            val workflow = WorkflowCache.parseYaml(definition)
            return DefinitionModel(
                namespace = workflow.namespace,
                name = workflow.name,
                version = workflow.version,
                definition = definition
            )
        }
    }
}
