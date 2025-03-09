package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import io.serverlessworkflow.api.types.TaskBase

data class TaskContext(
    val task: TaskBase,
    val rawInput: JsonNode,
    val position: TaskPosition
)