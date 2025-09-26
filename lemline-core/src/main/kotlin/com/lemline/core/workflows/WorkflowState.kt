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
     * A map of the current states (per position)
     */
    @SerialName("s") val currentStates: NodeStates

) : JsonSerializable {

    /**
     * Sets the output of the current task.
     */
    fun setCurrentTaskOutput(output: JsonElement) {
        currentStates[currentPosition]!!.rawOutput = output
    }

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        /**
         * Creates a new object describing a new instance
         */
        fun new(input: JsonElement) = WorkflowState(
            currentPosition = NodePosition.root,
            currentStates = NodeStates.new(input)
        )

        fun fromJsonString(json: String): WorkflowState = LemlineJson.decodeFromString(json)
    }
}
