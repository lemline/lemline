package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.ForTask

internal suspend fun WorkflowInstance.executeForTask(task: ForTask, input: JsonNode): JsonNode {
    TODO("Implement for loop execution")
} 