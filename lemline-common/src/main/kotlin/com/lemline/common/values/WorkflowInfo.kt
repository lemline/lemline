// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import io.serverlessworkflow.api.types.Workflow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowInfo(
    /**
     * The namespace of the workflow.
     */
    @SerialName("s") val namespace: WorkflowNamespace,

    /**
     * The name of the workflow.
     */
    @SerialName("n") val name: WorkflowName,

    /**
     * The version of the workflow.
     */
    @SerialName("v") val version: WorkflowVersion
) : JsonSerializable {

    override fun toJsonString() = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(json: String): WorkflowInfo = LemlineJson.decodeFromString(json)
    }

    val qualifiedName: String by lazy { "$namespace/$name:$version" }
}

val Workflow.info get() = WorkflowInfo(this.namespace, this.name, this.version)
