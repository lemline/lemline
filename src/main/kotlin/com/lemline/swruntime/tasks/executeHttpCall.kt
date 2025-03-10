package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.CallHTTP

internal suspend fun WorkflowInstance.executeHttpCall(task: CallHTTP, input: JsonNode): JsonNode {
    TODO("Implement HTTP call")
}