package com.lemline.runtime.messaging

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.runtime.json.Json
import com.lemline.runtime.json.toJackson
import com.lemline.runtime.serialization.ObjectNodeSerializer
import com.lemline.runtime.sw.tasks.JsonPointer
import com.lemline.runtime.sw.tasks.NodeState
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
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
 * @property states A map of the internal states (per position) of the workflow instance.
 * @property position The currentNodeInstance active position
 */
@Serializable
data class WorkflowMessage(
    @SerialName("n") val name: String,
    @SerialName("v") val version: String,
    @SerialName("s") val states: Map<JsonPointer, @Serializable(with = ObjectNodeSerializer::class) ObjectNode>,
    @SerialName("p") val position: JsonPointer
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
                JsonPointer.root to NodeState(
                    workflowId = id,
                    rawInput = input.toJackson(),
                    startedAt = DateTimeDescriptor.from(Instant.now())
                ).toJson()!!
            ),
            position = JsonPointer.root
        )

        fun fromJson(json: String): WorkflowMessage = Json.fromJson(json)
    }

    fun toJson() = Json.toJson(this)
}




