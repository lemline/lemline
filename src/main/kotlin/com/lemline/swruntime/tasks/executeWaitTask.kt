package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.WaitTask

internal suspend fun WorkflowInstance.executeWaitTask(task: WaitTask, input: JsonNode): JsonNode {
    TODO("Implement waiting")
} 