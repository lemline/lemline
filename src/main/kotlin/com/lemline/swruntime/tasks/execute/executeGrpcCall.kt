package com.lemline.swruntime.tasks.execute

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.CallGRPC

internal suspend fun WorkflowInstance.executeGrpcCall(task: CallGRPC, input: JsonNode): JsonNode {
    TODO("Implement gRPC call")
}