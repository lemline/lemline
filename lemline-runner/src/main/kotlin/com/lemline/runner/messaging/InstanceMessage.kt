// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message

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
data class InstanceMessage(
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
     * Indicates the id of the schedule model describing how this workflow should restart after its completion, if any
     */
    @SerialName("a") val scheduleId: String? = null,
    /**
     * Indicates the id of the parent model describing the workflow waiting for this workflow completion, if any.
     */
    @SerialName("w") val parentId: String? = null,
) {
    /**
     * The reactive message that has been deserialized to create this instance
     */
    lateinit var message: Message<*>

    // InstanceMessage is immutable, so we can cache the JSON string representation
    val payload: String by lazy { LemlineJson.encodeToString(this) }

    fun updateWith(workflowState: WorkflowState, workflowPosition: NodePosition?) = copy(
        workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
        workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
    )

    companion object {
        fun fromObjects(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: NodePosition,
            workflowState: WorkflowState,
            scheduleId: String?,
            parentId: String?,
        ) = InstanceMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            scheduleId = scheduleId,
            parentId = parentId,
        )

        fun fromStrings(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: String,
            workflowState: String,
            scheduleId: String?,
            parentId: String?,
        ) = InstanceMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            scheduleId = scheduleId,
            parentId = parentId,
        )

        fun forNewWorkflow(
            workflowId: String = IdGenerator.generateTimeBasedId(),
            workflowName: String,
            workflowVersion: String,
            workflowInput: JsonElement,
            scheduleId: String? = null,
            parentId: String? = null,
        ) = fromObjects(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState.newInstance(workflowInput),
            scheduleId = scheduleId,
            parentId = parentId,
        )

        fun fromMessage(message: Message<String>) = LemlineJson.decodeFromString<InstanceMessage>(message.payload)
            .also { it.message = message }
    }
}
