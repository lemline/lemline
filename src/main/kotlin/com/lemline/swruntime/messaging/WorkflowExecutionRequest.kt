package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

data class WorkflowExecutionRequest(
    val workflow: WorkflowRequest,
    val instance: InstanceRequest,
    val currentTask: TaskRequest?
)

data class WorkflowRequest(
    val name: String,
    val version: String,
)

data class InstanceRequest(
    val id: String,
    val rawInput: JsonNode,
    val context: ObjectNode,
    val startedAt: String, // Iso8601
)

data class TaskRequest(
    val rawInput: JsonNode,
    val position: String,
)
