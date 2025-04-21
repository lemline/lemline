// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions.scopes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Data class representing a task descriptor.
 *
 * @property name The name of the task.
 * @property reference The reference identifier of the task.
 * @property definition The definition of the task.
 * @property input The input JSON node for the task.
 * @property output The output JSON node for the task.
 * @property startedAt The date and time when the task started.
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#task-descriptor">Task Descriptor</a>
 */
@Serializable
data class TaskDescriptor(
    val name: String,
    val reference: String,
    val definition: JsonObject,
    val input: JsonElement?,
    var output: JsonElement?,
    val startedAt: JsonObject?
)
