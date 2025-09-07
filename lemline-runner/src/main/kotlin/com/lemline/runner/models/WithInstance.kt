// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.WithWorkflowInfo
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithInstance : WithId, WithWorkflowInfo {

    val workflowState: WorkflowState?

    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7?


    override val workflowId get() = workflowState?.workflowId

    override val workflowName get() = workflowState?.workflowName

    override val workflowVersion get() = workflowState?.workflowVersion
}
