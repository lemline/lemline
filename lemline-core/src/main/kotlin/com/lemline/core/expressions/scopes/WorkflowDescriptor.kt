package com.lemline.core.expressions.scopes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Data class representing a workflow descriptor.
 *
 * @property id The unique identifier of the workflow.
 * @property definition The definition of the workflow.
 * @property input The instanceRawInput JSON node for the workflow.
 * @property startedAt The date and time when the workflow started.
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#workflow-descriptor">Workflow Descriptor</a>
 */
@Serializable
data class WorkflowDescriptor(
    val id: String,
    val definition: JsonObject,
    val input: JsonElement,
    val startedAt: JsonObject,
)