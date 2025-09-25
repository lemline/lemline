// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowInfo(
    /**
     * The ID of the workflow.
     */
    @SerialName("i") val workflowId: WorkflowId,

    /**
     * The namespace of the workflow.
     */
    @SerialName("s") val workflowNamespace: WorkflowNamespace,

    /**
     * The name of the workflow.
     */
    @SerialName("n") val workflowName: WorkflowName,

    /**
     * The version of the workflow.
     */
    @SerialName("v") val workflowVersion: WorkflowVersion
) : JsonSerializable {
    /**
     * Creates a new instance with a new ID.
     */
    fun duplicate(newId: WorkflowId): WorkflowInfo = copy(workflowId = newId)

    override fun toJsonString() = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(json: String): WorkflowInfo = LemlineJson.decodeFromString(json)
    }
}
