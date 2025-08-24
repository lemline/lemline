// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.core.workflows.WorkflowState
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    @SerialName("i") @Contextual val workflowId: UUID,
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
     * Indicates the id of the parent model describing the workflow waiting for this workflow completion, if any.
     */
    @SerialName("w") @Contextual val parentId: UUID? = null,
) : WorkflowInstance {

    override val initialState by lazy { workflowState.parsed }
    override val initialPosition by lazy { workflowPosition.parsed }
    override val id by lazy { workflowId }
    override val name by lazy { workflowName }
    override val version by lazy { workflowVersion }

    /**
     * The reactive message that has been deserialized to create this instance
     */
    @Transient
    lateinit var message: Message<*>

    // InstanceMessage is immutable, so we can cache the JSON string representation
    val payload: String by lazy { LemlineJson.encodeToString(this) }

    fun updateWith(workflowState: WorkflowState, workflowPosition: NodePosition?): InstanceMessage = copy(
        workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
        workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
    ).also { it.message = message }

    companion object {
        fun fromObjects(
            workflowId: UUID,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: NodePosition,
            workflowState: WorkflowState,
            parentId: UUID?,
        ) = InstanceMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            parentId = parentId,
        )

        fun fromStrings(
            workflowId: UUID,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: String,
            workflowState: String,
            parentId: UUID?,
        ) = InstanceMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.serializer()),
            workflowState = LazyParsedField(workflowState, WorkflowState.serializer()),
            parentId = parentId,
        )

        fun forNewWorkflow(
            workflowId: UUID = IdGenerator.generateUUIDV7(),
            workflowName: String,
            workflowVersion: String,
            workflowInput: JsonElement,
            parentId: UUID? = null,
        ) = fromObjects(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState.newInstance(workflowInput),
            parentId = parentId,
        )

        fun fromMessage(message: Message<String>) = LemlineJson.decodeFromString<InstanceMessage>(message.payload)
            .also { it.message = message }
    }
}
