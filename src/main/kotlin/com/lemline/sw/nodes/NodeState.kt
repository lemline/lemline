package com.lemline.sw.nodes

import com.lemline.common.json.DateTimeDescriptorSerializer
import com.lemline.common.json.Json
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a node's states.
 * Contain states variables for the task during execution.
 * Some value such as forIndex as specific to a specific task
 */
@Serializable
data class NodeState(
    @SerialName(VARIABLES)
    var variables: JsonObject = Json.jsonObject,
    @SerialName(ATTEMPT_INDEX)
    var attemptIndex: Int = ATTEMPT_INDEX_DEFAULT,
    @SerialName(CHILD_INDEX)
    var childIndex: Int = CHILD_INDEX_DEFAULT,
    @SerialName(RAW_INPUT)
    var rawInput: JsonElement? = null,
    @SerialName(RAW_OUTPUT)
    var rawOutput: JsonElement? = null,
    @SerialName(CONTEXT)
    var context: JsonObject = Json.jsonObject,
    @SerialName(WORKFLOW_ID)
    var workflowId: String? = null,
    @SerialName(STARTED_AT)
    @Serializable(with = DateTimeDescriptorSerializer::class)
    var startedAt: DateTimeDescriptor? = null,
    @SerialName(FOR_INDEX)
    var forIndex: Int = FOR_INDEX_DEFAULT,
) {
    /**
     * Those constants MUST NOT be changed to ensure backward compatibility of messages
     */
    companion object {
        const val CHILD_INDEX = "i"
        const val ATTEMPT_INDEX = "try"
        const val VARIABLES = "var"
        const val RAW_INPUT = "inp"
        const val RAW_OUTPUT = "out"
        const val CONTEXT = "ctx"
        const val WORKFLOW_ID = "wid"
        const val STARTED_AT = "sat"
        const val FOR_INDEX = "fori"

        const val CHILD_INDEX_DEFAULT = -1
        const val ATTEMPT_INDEX_DEFAULT = 0
        const val FOR_INDEX_DEFAULT = -1
    }
}
