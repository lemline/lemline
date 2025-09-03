// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.runner.messaging.JsonSerializable
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
 * @property nodeStates A map of the internal initial states (per position).
 * @property workflowPosition The current active initial position
 */
@ExperimentalTime
@Serializable
data class InstanceMessage(
    /**
     * The ID of the workflow.
     */
    @SerialName("i") @Contextual override val workflowId: UUID,
    /**
     * The name of the workflow.
     */
    @SerialName("n") override val workflowName: String,
    /**
     * The version of the workflow.
     */
    @SerialName("v") override val workflowVersion: String,
    /**
     * The current active initial position
     */
    @SerialName("p") val workflowPosition: LazyParsedField<NodePosition>,
    /**
     * A map of the internal initial states (per position)
     */
    @SerialName("s") val nodeStates: LazyParsedField<NodeStates>,
    /**
     * Indicates the id of the parent model describing the workflow waiting for this workflow completion, if any.
     */
    @SerialName("w") @Contextual val parentId: UUID? = null,
) : WorkflowInstance, JsonSerializable {

    override val currentState by lazy { nodeStates.parsed }

    override val currentPosition by lazy { workflowPosition.parsed }

    @Transient
    lateinit var message: Message<String>

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    fun updateWith(nodeStates: NodeStates, workflowPosition: NodePosition): InstanceMessage = copy(
        workflowPosition = LazyParsedField(workflowPosition, NodePosition.Companion.serializer()),
        nodeStates = LazyParsedField(nodeStates, NodeStates.Companion.serializer()),
    ).also { it.message = message }

    companion object {
        fun fromObjects(
            workflowId: UUID,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: NodePosition,
            nodeStates: NodeStates,
            parentId: UUID?,
        ) = InstanceMessage(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.Companion.serializer()),
            nodeStates = LazyParsedField(nodeStates, NodeStates.Companion.serializer()),
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
            workflowPosition = LazyParsedField(workflowPosition, NodePosition.Companion.serializer()),
            nodeStates = LazyParsedField(workflowState, NodeStates.Companion.serializer()),
            parentId = parentId,
        )

        fun forNewWorkflow(
            workflowId: UUID = IdGenerator.generateV7(),
            workflowName: String,
            workflowVersion: String,
            workflowInput: JsonElement,
            parentId: UUID? = null,
        ) = fromObjects(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowPosition = NodePosition.root,
            nodeStates = NodeStates.newInstance(workflowInput),
            parentId = parentId,
        )

        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<InstanceMessage>(jsonString)

        fun fromMessage(message: Message<String>) = fromJsonString(message.payload).also { it.message = message }
    }
}
