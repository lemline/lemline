package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.CallFunction

internal suspend fun WorkflowInstance.executeFunctionCall(task: CallFunction, input: JsonNode): JsonNode {
    TODO("Implement custom function call")
} 