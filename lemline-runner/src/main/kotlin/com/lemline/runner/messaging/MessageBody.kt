// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.core.workflows.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a message containing information about a workflow execution.
 *
 * This message is sent immediately after task completion
 * to prevent any errors in the workflow execution from causing a duplicate task execution.
 *
 * @property workflowName The name of the workflow.
 * @property workflowVersion The version of the workflow.
 * @property workflowState A map of the internal initial states (per position).
 * @property workflowPosition The current active initial position
 */
@ExperimentalTime
@Serializable
class MessageBody(
    @SerialName("i") val workflowId: String,
    @SerialName("n") val workflowName: String,
    @SerialName("v") val workflowVersion: String,
    @SerialName("p") val workflowPosition: LazyParsedField<NodePosition>,
    @SerialName("s") val workflowState: LazyParsedField<WorkflowState>,
) {

    override fun equals(other: Any?): Boolean = (other is MessageBody) && this.workflowId == other.workflowId &&
        this.workflowName == other.workflowName &&
        this.workflowVersion == other.workflowVersion &&
        this.workflowPosition.serialized == other.workflowPosition.serialized &&
        this.workflowState.serialized == other.workflowState.serialized

    override fun hashCode(): Int {
        var result = workflowId.hashCode()
        result = 31 * result + workflowName.hashCode()
        result = 31 * result + workflowVersion.hashCode()
        result = 31 * result + workflowPosition.serialized.hashCode()
        result = 31 * result + workflowState.serialized.hashCode()
        return result
    }

    override fun toString() = jsonString

    companion object {
        fun fromObjects(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: NodePosition,
            workflowState: WorkflowState,
        ) = MessageBody(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
        )

        fun fromStrings(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: String,
            workflowState: String,
        ) = MessageBody(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
        )

        fun newInstance(
            id: String = IdGenerator.generateTimeBasedId(),
            name: String,
            version: String,
            input: JsonElement,
            parentId: String? = null,
            parentIsWaiting: Boolean = false,
        ) = fromObjects(
            workflowId = id,
            workflowName = name,
            workflowVersion = version,
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState.newInstance(input, parentId, parentIsWaiting),
        )

        fun fromJsonString(json: String): MessageBody = LemlineJson.decodeFromString(json)
    }

    // Message is immutable, so we can cache the JSON string representation
    val jsonString: String by lazy { LemlineJson.encodeToString(this) }

    // Message is immutable, so we can cache the JSON string representation in pretty format
    val jsonPrettyString: String by lazy { LemlineJson.encodeToPrettyString(this) }

    fun toWorkflowInstance(secrets: Map<String, JsonElement>) = WorkflowInstance(
        id = workflowId,
        name = workflowName,
        version = workflowVersion,
        state = workflowState.parsed,
        position = workflowPosition.parsed,
        secrets = secrets
    )
}

@ExperimentalTime
internal fun WorkflowInstance.toMessage() = MessageBody.fromObjects(
    workflowId = this.id,
    workflowName = this.name,
    workflowVersion = this.version,
    workflowPosition = this.position!!,
    workflowState = this.state,
)
