package com.lemline.swruntime.expressions.scopes

import com.fasterxml.jackson.databind.JsonNode
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor

/**
 * Data class representing a task descriptor.
 *
 * @property name The name of the task.
 * @property reference The reference identifier of the task.
 * @property definition The definition of the task.
 * @property rawInput The raw instanceRawInput JSON node for the task.
 * @property rawOutput The raw output JSON node for the task.
 * @property startedAt The date and time when the task started.
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#task-descriptor">Task Descriptor</a>
 */
data class TaskDescriptor(
    val name: String,
    val reference: String,
    val definition: TaskBase,
    val rawInput: JsonNode,
    val rawOutput: JsonNode?,
    val startedAt: DateTimeDescriptor
)