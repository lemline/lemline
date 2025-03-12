package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.SetTask

internal suspend fun WorkflowInstance.executeSetTask(task: SetTask, input: JsonNode): JsonNode {
    TODO("Implement context setting")
} 