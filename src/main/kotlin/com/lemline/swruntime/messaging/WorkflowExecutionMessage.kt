package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.tasks.JsonPointer
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import java.time.Instant

/**
 * Represents a message containing information about a workflow execution.
 *
 * This message is sent immediately after task completion
 * to prevent any errors in the workflow execution from causing a duplicate task execution.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property state A map of the internal state (per position) of the workflow instance.
 * @property position The current active position
 */
data class WorkflowExecutionMessage(
    val name: String,
    val version: String,
    val state: Map<JsonPointer, NodeState>,
    val position: JsonPointer
) {
    companion object {
        fun create(
            name: String,
            version: String,
            id: String,
            input: JsonNode
        ) = WorkflowExecutionMessage(
            name = name,
            version = version,
            state = mapOf(
                JsonPointer.root to NodeState().apply {
                    setId(id)
                    setRawInput(input)
                    setStartedAt(DateTimeDescriptor.from(Instant.now()))
                }),
            position = JsonPointer.root
        )
    }
}


