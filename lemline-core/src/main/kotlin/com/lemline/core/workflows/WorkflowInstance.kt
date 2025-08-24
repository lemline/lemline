// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.NodePosition
import java.util.*
import kotlin.time.ExperimentalTime

interface WorkflowInstance {
    /**
     * The unique ID of the workflow.
     */
    val id: UUID

    /**
     * The name of the workflow.
     */
    val name: String

    /**
     * The version of the workflow.
     */
    val version: String

    /**
     * The current active initial position
     */
    val initialPosition: NodePosition

    /**
     * A map of the internal initial states (per position)
     */
    @ExperimentalTime
    val initialState: WorkflowState
}
