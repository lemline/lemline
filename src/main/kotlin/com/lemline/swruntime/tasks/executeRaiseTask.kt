package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.RaiseTask

internal suspend fun WorkflowInstance.executeRaiseTask(task: RaiseTask, input: JsonNode): JsonNode {
    TODO("Implement error raising")
} 