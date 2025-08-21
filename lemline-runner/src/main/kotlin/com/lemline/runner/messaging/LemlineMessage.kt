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
 * @property workflowName The name of the workflow.
 * @property workflowVersion The version of the workflow.
 * @property workflowState A map of the internal initial states (per position).
 * @property workflowPosition The current active initial position
 */
@ExperimentalTime
@Serializable
data class LemlineMessage(
    /**
     * The ID of the workflow.
     */
    @SerialName("i") val workflowId: String,
    /**
     * The name of the workflow.
     */
    @SerialName("n") val workflowName: String,
    /**
     * The version of the workflow.
     */
    @SerialName("v") val workflowVersion: String,
    /**
     * The current active initial position
     */
    @SerialName("p") val workflowPosition: LazyParsedField<NodePosition>,
    /**
     * A map of the internal initial states (per position)
     */
    @SerialName("s") val workflowState: LazyParsedField<WorkflowState>,
    /**
     * Indicates whether the workflow is scheduled to restart after its completion.
     */
    @SerialName("a") val isScheduledAfter: Boolean = false,
) {
    override fun toString() = jsonString

    companion object {
        fun fromObjects(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: NodePosition,
            workflowState: WorkflowState,
            isScheduledAfter: Boolean,
        ) = LemlineMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            isScheduledAfter = isScheduledAfter,
        )

        fun fromStrings(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: String,
            workflowState: String,
            isScheduledAfter: Boolean,
        ) = LemlineMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            isScheduledAfter = isScheduledAfter,
        )

        fun create(
            workflowId: String = IdGenerator.generateTimeBasedId(),
            workflowName: String,
            workflowVersion: String,
            workflowInput: JsonElement,
            parentId: String? = null,
            parentIsWaiting: Boolean = false,
            isScheduledAfter: Boolean = false,
        ) = fromObjects(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState.newInstance(workflowInput, parentId, parentIsWaiting),
            isScheduledAfter = isScheduledAfter,
        )

        fun fromJsonString(json: String): LemlineMessage = LemlineJson.decodeFromString(json)
    }

    // MessageBody is immutable, so we can cache the JSON string representation
    val jsonString: String by lazy { LemlineJson.encodeToString(this) }

    // MessageBody is immutable, so we can cache the JSON string representation in pretty format
    val jsonPrettyString: String by lazy { LemlineJson.encodeToPrettyString(this) }

    fun toWorkflowInstance(secrets: Map<String, JsonElement>) = WorkflowInstance(
        id = workflowId,
        name = workflowName,
        version = workflowVersion,
        state = workflowState.parsed,
        position = workflowPosition.parsed,
        secrets = secrets,
        isScheduledAfter = isScheduledAfter,
    )
}

@ExperimentalTime
internal fun WorkflowInstance.toMessage() = LemlineMessage.fromObjects(
    workflowId = this.id,
    workflowName = this.name,
    workflowVersion = this.version,
    workflowPosition = this.position!!,
    workflowState = this.state,
    isScheduledAfter = this.isScheduledAfter
)
