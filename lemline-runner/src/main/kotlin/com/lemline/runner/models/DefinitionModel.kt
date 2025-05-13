// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.github.f4b6a3.uuid.UuidCreator
import com.lemline.core.json.LemlineJson
import io.serverlessworkflow.api.types.Workflow

const val DEFINITION_TABLE = "lemline_definitions"

data class DefinitionModel(
    override val id: String = UuidCreator.getTimeOrderedEpoch().toString(),

    val name: String,

    val version: String,

    val definition: String
) : UuidV7Entity() {
    companion object {
        fun from(workflow: Workflow) = DefinitionModel(
            name = workflow.document.name,
            version = workflow.document.version,
            definition = LemlineJson.yamlMapper.writeValueAsString(workflow)
        )
    }
}
