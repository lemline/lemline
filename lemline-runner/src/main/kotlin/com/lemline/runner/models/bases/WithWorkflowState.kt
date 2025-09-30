// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models.bases

import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.repositories.capabilities.OptionalStateColumns
import com.lemline.runner.repositories.capabilities.StateColumns
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithWorkflowState : StateColumns {
    val workflowState: WorkflowState

    override val nodePosition get() = workflowState.currentPosition

    override val nodeStates get() = workflowState.currentStates
}

@ExperimentalTime
interface WithOptionalWorkflowState : OptionalStateColumns {

    val workflowState: WorkflowState?

    override val nodePosition get() = workflowState?.currentPosition

    override val nodeStates get() = workflowState?.currentStates
}
