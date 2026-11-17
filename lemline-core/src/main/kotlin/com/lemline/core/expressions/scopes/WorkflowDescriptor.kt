// SPDX-License-Identifier: BUSL-1.1
@file:kotlinx.serialization.UseSerializers(
    com.lemline.common.json.JsonNodeSerializer::class,
    com.lemline.common.json.ObjectNodeSerializer::class,
    com.lemline.common.json.ArrayNodeSerializer::class,
)

package com.lemline.core.expressions.scopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import kotlinx.serialization.Serializable
/**
 * Data class representing a workflow descriptor.
 *
 * @property id The unique identifier of the workflow.
 * @property definition The definition of the workflow.
 * @property input The instanceRawInput JSON node for the workflow.
 * @property startedAt The date and time when the workflow started.
 *
 * @see <a href="https://github.com/open-workflow-specification/specification/blob/main/dsl.md#workflow-descriptor">Workflow Descriptor</a>
 */
@Serializable
data class WorkflowDescriptor(
    val id: String,
    val definition: ObjectNode,
    val input: JsonNode,
    private val startedAt: ObjectNode,
)
