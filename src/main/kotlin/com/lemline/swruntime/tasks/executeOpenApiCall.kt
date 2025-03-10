package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.CallOpenAPI

internal suspend fun WorkflowInstance.executeOpenApiCall(task: CallOpenAPI, input: JsonNode): JsonNode {
    TODO("Implement OpenAPI call")
}