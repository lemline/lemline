package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.RunTask

internal suspend fun WorkflowInstance.executeRunTask(task: RunTask, input: JsonNode): JsonNode {
    TODO("Implement container/script/shell execution")
} 