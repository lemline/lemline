package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.tasks.TaskPosition

data class TaskRequest2(
    val workflowName: String,
    val workflowVersion: String,
    val instanceId: String,
    val taskRawInput: JsonNode,
    val taskPosition: TaskPosition?
) 