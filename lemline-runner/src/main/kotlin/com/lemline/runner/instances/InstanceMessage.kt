// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.messaging.JsonSerializable
import com.lemline.runner.models.IDV7
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
@Serializable
data class InstanceMessage(
    /**
     * Description of the workflow instance
     */
    @SerialName("i") val workflowInstance: WorkflowInstance,

    /**
     * Parent's model ID waiting for this instance completion, if any.
     */
    @SerialName("p") val parentId: IDV7? = null,
) : JsonSerializable {

    @Transient
    lateinit var message: Message<String>

    @Transient
    val workflowId = workflowInstance.workflowId

    @Transient
    val workflowName = workflowInstance.workflowName

    @Transient
    val workflowVersion = workflowInstance.workflowVersion

    @Transient
    val currentPosition = workflowInstance.currentPosition

    @Transient
    val currentStates = workflowInstance.currentStates

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    fun updateWith(
        currentPosition: NodePosition,
        currentStates: NodeStates
    ): InstanceMessage = copy(
        workflowInstance = workflowInstance.copy(currentPosition = currentPosition, currentStates = currentStates),
    ).also { it.message = message }

    companion object {
        fun fromObjects(
            workflowId: WorkflowId,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            currentPosition: NodePosition,
            currentStates: NodeStates,
            parentId: IDV7?,
        ) = InstanceMessage(
            workflowInstance = WorkflowInstance(
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                currentPosition = currentPosition,
                currentStates = currentStates,
            ),
            parentId = parentId,
        )

        fun fromStrings(
            workflowId: UUID,
            workflowName: String,
            workflowVersion: String,
            workflowPosition: String,
            workflowState: String,
            parentId: IDV7?,
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
            parentId: IDV7? = null,
        ) = fromObjects(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            currentPosition = NodePosition.root,
            currentStates = NodeStates.newInstance(workflowInput),
            parentId = parentId,
        )

        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<InstanceMessage>(jsonString)

        fun fromMessage(message: Message<String>) = fromJsonString(message.payload).also { it.message = message }
    }
}
