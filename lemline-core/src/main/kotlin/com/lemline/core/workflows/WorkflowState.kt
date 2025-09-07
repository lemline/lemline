// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
@ExperimentalTime
data class WorkflowState(
    /**
     * The ID of the workflow.
     */
    @SerialName("i") val workflowId: WorkflowId,

    /**
     * The name of the workflow.
     */
    @SerialName("n") val workflowName: WorkflowName,

    /**
     * The version of the workflow.
     */
    @SerialName("v") val workflowVersion: WorkflowVersion,

    /**
     * The current position
     */
    @SerialName("p") val currentPosition: NodePosition,

    /**
     * A map of the current states (per position)
     */
    @SerialName("s") val currentStates: NodeStates,
) {
    /**
     * Sets the output of the current task.
     */
    fun setCurrentTaskOutput(output: JsonElement) {
        currentStates[currentPosition]!!.rawOutput = output
    }

    /**
     * Creates a new instance of the workflow state with a new ID.
     */
    fun duplicate(newId: WorkflowId): WorkflowState = copy(workflowId = newId)

    fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        /**
         * Creates a new object describing a new instance
         */
        fun new(
            workflowId: WorkflowId,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            input: JsonElement,
        ) = WorkflowState(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            currentPosition = NodePosition.root,
            currentStates = NodeStates.new(input)
        )
    }
}
