// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.instances.InstanceMessage
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithInstance : WithId {
    val instanceMessage: InstanceMessage?

    /**
     * The ID of the workflow.
     */
    val workflowId: WorkflowId? get() = instanceMessage?.workflowInstance?.workflowId

    /**
     * The name of the workflow.
     */
    val workflowName: WorkflowName? get() = instanceMessage?.workflowInstance?.workflowName

    /**
     * The version of the workflow.
     */
    val workflowVersion: WorkflowVersion? get() = instanceMessage?.workflowInstance?.workflowVersion

    /**
     * The current active initial position
     */
    val currentPosition: NodePosition? get() = instanceMessage?.workflowInstance?.currentPosition

    /**
     * A map of the internal initial states (per position)
     */
    val currentStates: NodeStates? get() = instanceMessage?.workflowInstance?.currentStates

    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7? get() = instanceMessage?.parentId
}

