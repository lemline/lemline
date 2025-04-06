package com.lemline.worker.messaging

import com.lemline.common.json.Json
import com.lemline.sw.nodes.NodePosition
import com.lemline.sw.nodes.NodeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Represents a message containing information about a workflow execution.
 *
 * This message is sent immediately after task completion
 * to prevent any errors in the workflow execution from causing a duplicate task execution.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property states A map of the internal initialStates (per initialPosition) of the workflow initialPosition.
 * @property position The currentNodeInstance active initialPosition
 */
@Serializable
data class WorkflowMessage(
    @SerialName("n") val name: String,
    @SerialName("v") val version: String,
    @SerialName("s") val states: Map<NodePosition, NodeState>,
    @SerialName("p") val position: NodePosition
) {
    companion object {
        fun newInstance(
            name: String,
            version: String,
            id: String,
            input: JsonElement,
        ) = WorkflowMessage(
            name = name,
            version = version,
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = id,
                    rawInput = input,
                    startedAt = Instant.now()
                )
            ),
            position = NodePosition.root
        )

        fun fromJsonString(json: String): WorkflowMessage = Json.decodeFromString(json)
    }

    fun toJsonString() = Json.encodeToString(this)
}




