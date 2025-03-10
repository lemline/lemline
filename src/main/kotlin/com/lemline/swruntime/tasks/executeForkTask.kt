package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.ForkTask

internal suspend fun WorkflowInstance.executeForkTask(task: ForkTask, input: JsonNode): JsonNode {
    TODO("Implement parallel execution")
} 