package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
 * @property states A map of the internal states (per position) of the workflow instance.
 * @property position The current active position
 */
data class WorkflowExecutionMessage(
    val name: String,
    val version: String,
    val states: Map<JsonPointer, ObjectNode>,
    val position: JsonPointer
) {
    companion object {
        fun newInstance(
            name: String,
            version: String,
            id: String,
            input: JsonNode
        ) = WorkflowExecutionMessage(
            name = name,
            version = version,
            states = mapOf(
                JsonPointer.root to NodeState(
                    workflowId = id,
                    rawInput = input,
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!
            ),
            position = JsonPointer.root
        )
    }
}


