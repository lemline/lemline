// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lemline.core.expressions.EvaluationMode
import io.serverlessworkflow.api.types.Workflow

/** Carries the DSL's evaluate configuration until the SDK model supports it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
class EvaluatedWorkflow : Workflow() {
    @get:JsonProperty("evaluate")
    @set:JsonProperty("evaluate")
    var evaluate: JsonNode? = null

    @get:JsonIgnore
    val evaluationMode: EvaluationMode
        get() = when (evaluate?.get("mode")?.textValue()) {
            "loose" -> EvaluationMode.LOOSE
            else -> EvaluationMode.STRICT
        }
}
