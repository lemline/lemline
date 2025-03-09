package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode

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
    val context: Map<String, JsonNode>,
    val startedAt: String, // Iso8601
)

data class TaskRequest(
    val rawInput: JsonNode,
    val position: String,
)
