package com.lemline.sw.expressions.scopes

import com.fasterxml.jackson.databind.JsonNode
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor

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
data class WorkflowDescriptor(
    val id: String,
    val definition: Workflow,
    val input: JsonNode,
    val startedAt: DateTimeDescriptor
)