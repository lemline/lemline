// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.PositionPointer
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.MessageBody

abstract class InstanceModel : IdModel() {
    abstract val workflowId: String
    abstract val workflowVersion: String
    abstract val workflowName: String
    abstract val workflowPosition: String
    abstract val workflowState: String

    fun toMessageBody() = MessageBody.fromStrings(
        workflowId = workflowId,
        workflowName = workflowName,
        workflowVersion = workflowVersion,
        workflowPosition = workflowPosition,
        workflowState = workflowState
    )

    val state: WorkflowState by lazy { WorkflowState.fromJsonString(workflowState) }

    val position: NodePosition by lazy { PositionPointer(workflowPosition).toPosition() }
}
