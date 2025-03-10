package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.SwitchTask

internal suspend fun WorkflowInstance.executeSwitchTask(task: SwitchTask, input: JsonNode): JsonNode {
    TODO("Implement conditional branching")
} 