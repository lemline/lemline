// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@ExperimentalTime
data class WorkflowInstance(
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
)
