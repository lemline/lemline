package com.lemline.runner.messaging

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithDefiniteWorkflowInfo
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
@Serializable
data class InstanceMessage<S : WorkflowState>(
    /**
     * Description of the workflow instance
     */
    @SerialName("i") override val workflowInfo: WorkflowInfo,

    /**
     * Description of the workflow instance
     */
    @SerialName("s") val workflowState: S,

    /**
     * Parent's model ID waiting for this instance completion, if any.
     */
    @SerialName("p") val parentId: IDV7? = null
) : WithDefiniteWorkflowInfo, JsonSerializable {

    @Transient
    lateinit var message: Message<String>

    override fun toJsonString(): String {
        // Use polymorphic serializer for WorkflowState to handle sealed class hierarchy
        @Suppress("UNCHECKED_CAST")
        val serializer =
            serializer(kotlinx.serialization.serializer<WorkflowState>()) as KSerializer<InstanceMessage<S>>
        return LemlineJson.json.encodeToString(serializer, this)
    }

    companion object {

        fun new(
            workflowId: WorkflowId = WorkflowId.random(),
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
            workflowState = WorkflowCommand.start(workflowInput),
            parentId = parentId,
        )

        inline fun <reified S : WorkflowState> fromJsonString(jsonString: String): InstanceMessage<S> {
            // Use polymorphic serializer for WorkflowState to handle sealed class hierarchy
            @Suppress("UNCHECKED_CAST")
            val serializer =
                serializer(kotlinx.serialization.serializer<WorkflowState>()) as KSerializer<InstanceMessage<S>>
            return LemlineJson.json.decodeFromString(serializer, jsonString)
        }

        inline fun <reified S : WorkflowState> fromMessage(message: Message<String>): InstanceMessage<S> =
            fromJsonString<S>(message.payload).also { it.message = message }
    }
}
