package com.lemline.swruntime.messaging

import com.lemline.swruntime.tasks.JsonPointer
import com.lemline.swruntime.tasks.NodeState

/**
 * Represents a message containing information about a workflow execution.
 *
 * This message is sent immediately after task completion
 * to prevent any errors in the workflow execution from causing a duplicate task execution.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property id The unique identifier of the workflow instance.
 * @property state A map of the internal state (per position) of the workflow instance.
 * @property position The current active position
 */
data class WorkflowExecutionMessage(
    val name: String,
    val version: String,
    val state: Map<JsonPointer, NodeState>,
    val position: JsonPointer
)


