package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.TryTask

internal suspend fun WorkflowInstance.executeTryTask(task: TryTask, input: JsonNode): JsonNode {
    TODO("Implement try-catch execution")
} 