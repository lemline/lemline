// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.PositionPointer
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.LemlineMessage
import kotlin.time.ExperimentalTime

@ExperimentalTime
abstract class InstanceModel : IdModel() {
    abstract val workflowId: String
    abstract val workflowVersion: String
    abstract val workflowName: String
    abstract val workflowPosition: String
    abstract val workflowState: String

    open fun toLemlineMessage() = LemlineMessage.fromStrings(
        workflowId = workflowId,
        workflowName = workflowName,
        workflowVersion = workflowVersion,
        workflowPosition = workflowPosition,
        workflowState = workflowState,
        isScheduledAfter = false,
    )

    val state: WorkflowState by lazy { WorkflowState.fromJsonString(workflowState) }

    val position: NodePosition by lazy { PositionPointer(workflowPosition).toPosition() }
}
