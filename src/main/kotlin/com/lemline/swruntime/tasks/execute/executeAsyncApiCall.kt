package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.CallAsyncAPI

internal suspend fun WorkflowInstance.executeAsyncApiCall(task: CallAsyncAPI, input: JsonNode): JsonNode {
    TODO("Implement AsyncAPI call")
} 