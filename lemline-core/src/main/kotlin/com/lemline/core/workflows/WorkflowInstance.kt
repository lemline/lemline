// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable

@Serializable
@ExperimentalTime
data class WorkflowInstance(
    val workflowId: WorkflowId,
    val workflowName: WorkflowName,
    val workflowVersion: WorkflowVersion,
    val currentPosition: NodePosition,
    val currentStates: NodeStates
)
