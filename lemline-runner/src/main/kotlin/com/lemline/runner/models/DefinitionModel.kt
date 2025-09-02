// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import io.serverlessworkflow.api.types.Workflow

data class DefinitionModel(
    val name: String,

    val version: String,

    val definition: String
) {
    companion object {
        fun from(workflow: Workflow) = DefinitionModel(
            name = workflow.document.name,
            version = workflow.document.version,
            definition = LemlineJson.yamlMapper.writeValueAsString(workflow)
        )
    }
}
