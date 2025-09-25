// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithInstanceInfo
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processor.Processor
import com.lemline.core.workflows.WorkflowState
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
    @SerialName("i") override val workflowInfo: WorkflowInfo,
    /**
     * Description of the workflow instance
     */
    @SerialName("s") val workflowState: WorkflowState,

    /**
     * Parent's model ID waiting for this instance completion, if any.
     */
    @SerialName("p") val parentId: IDV7? = null
) : WithInstanceInfo, JsonSerializable {

    @Transient
    lateinit var message: Message<String>

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    /**
     * Updates the workflow state with the given processor's current position and states.
     */
    fun updateFrom(processor: Processor): InstanceMessage = copy(
        workflowState = workflowState.copy(
            currentPosition = processor.position!!,
            currentStates = processor.state,
        )
    ).also { it.message = message }

    override val workflowId: WorkflowId get() = workflowInfo.workflowId

    override val workflowNamespace: WorkflowNamespace get() = workflowInfo.workflowNamespace

    override val workflowName: WorkflowName get() = workflowInfo.workflowName

    override val workflowVersion: WorkflowVersion get() = workflowInfo.workflowVersion

    companion object {

        fun new(
            workflowId: WorkflowId,
            workflowNamespace: WorkflowNamespace,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            workflowInput: JsonElement,
            parentId: IDV7? = null,
        ) = InstanceMessage(
            WorkflowInfo(
                workflowId = workflowId,
                workflowNamespace = workflowNamespace,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
            ),
            workflowState = WorkflowState.new(
                input = workflowInput,
            ),
            parentId = parentId,
        )

        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<InstanceMessage>(jsonString)

        fun fromMessage(message: Message<String>) = fromJsonString(message.payload).also { it.message = message }
    }
}
