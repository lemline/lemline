package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.ListenTask

internal suspend fun WorkflowInstance.executeListenTask(task: ListenTask, input: JsonNode): JsonNode {
    TODO("Implement event listening")
} 