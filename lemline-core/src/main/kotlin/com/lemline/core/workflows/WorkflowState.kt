// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
@ExperimentalTime
data class WorkflowState(
    /**
     * The current position
     */
    @SerialName("p") val currentPosition: NodePosition,

    /**
     * The current data
     */
    @SerialName("d") val currentData: JsonElement,

    /**
     * A map of the current states (per position)
     */
    @SerialName("s") val currentStates: PositionStates

) : JsonSerializable {


    /**
     * Converts this `WorkflowState` object into its JSON string representation.
     */
    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    /**
     * Method for deserializing a `WorkflowState` object from a JSON string.
     */
    companion object {
        fun fromJsonString(json: String): WorkflowState = LemlineJson.decodeFromString(json)
    }
}
