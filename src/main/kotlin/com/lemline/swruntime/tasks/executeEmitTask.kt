package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.EmitTask

internal suspend fun WorkflowInstance.executeEmitTask(task: EmitTask, input: JsonNode): JsonNode {
    TODO("Implement event emission")
} 