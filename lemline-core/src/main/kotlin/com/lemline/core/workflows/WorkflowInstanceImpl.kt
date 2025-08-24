// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime

@ExperimentalTime
data class WorkflowInstanceImpl(
    override val id: String,
    override val name: String,
    override val version: String,
    override val initialPosition: NodePosition,
    override val initialState: WorkflowState
) : WorkflowInstance
