// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processor.Processor
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.JsonSerializable
import com.lemline.runner.messaging.WithWorkflowInfo
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
    @SerialName("s") val workflowState: WorkflowState,

    /**
     * Parent's model ID waiting for this instance completion, if any.
     */
    @SerialName("p") val parentId: IDV7? = null
) : WithWorkflowInfo, JsonSerializable {

    @Transient
    lateinit var message: Message<String>

    @Transient
    override val workflowName = workflowState.workflowName

    @Transient
    override val workflowVersion = workflowState.workflowVersion

    @Transient
    override val workflowId = workflowState.workflowId

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

    companion object {

        fun new(
            workflowId: WorkflowId,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            workflowInput: JsonElement,
            parentId: IDV7? = null,
        ) = InstanceMessage(
            workflowState = WorkflowState.new(
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                input = workflowInput,
            ),
            parentId = parentId,
        )

        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<InstanceMessage>(jsonString)

        fun fromMessage(message: Message<String>) = fromJsonString(message.payload).also { it.message = message }
    }
}
