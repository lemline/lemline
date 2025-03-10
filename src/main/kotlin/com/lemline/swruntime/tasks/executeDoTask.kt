package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.workflows.WorkflowInstance
import io.serverlessworkflow.api.types.DoTask

internal suspend fun WorkflowInstance.executeDoTask(task: DoTask, input: JsonNode): JsonNode {
    var currentInput = input
    for (taskItem in task.`do`) {
        //currentInput = executeTask(taskItem.toTask(), currentInput)
    }
    return currentInput
} 