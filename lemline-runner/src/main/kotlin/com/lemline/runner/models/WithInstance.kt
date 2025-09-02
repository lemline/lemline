// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.instances.InstanceMessage
import java.util.*
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithInstance : WithId {
    val instance: InstanceMessage?

    /**
     * The ID of the workflow.
     */
    val workflowId: UUID get() = instance!!.workflowId

    /**
     * The name of the workflow.
     */
    val workflowName: String get() = instance!!.workflowName

    /**
     * The version of the workflow.
     */
    val workflowVersion: String get() = instance!!.workflowVersion

    /**
     * The current active initial position
     */
    val workflowPosition: NodePosition get() = instance!!.workflowPosition.parsed

    /**
     * A map of the internal initial states (per position)
     */
    val workflowState: WorkflowState get() = instance!!.workflowState.parsed
}
