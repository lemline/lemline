package com.lemline.worker.messaging

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a message containing information about a workflow execution.
 *
 * This message is sent immediately after task completion
 * to prevent any errors in the workflow execution from causing a duplicate task execution.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property states A map of the internal initial states (per position).
 * @property position The current active initial position
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
                    startedAt = Clock.System.now(),
                )
            ),
            position = NodePosition.root
        )

        fun fromJsonString(json: String): WorkflowMessage = LemlineJson.decodeFromString(json)
    }

    fun toJsonString(): String = LemlineJson.encodeToString(this)
}
