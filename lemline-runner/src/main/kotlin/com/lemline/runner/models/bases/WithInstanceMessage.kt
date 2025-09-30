// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models.bases

import com.lemline.common.values.WorkflowInfo
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithInstanceMessage : WithWorkflowInfo, WithWorkflowState {

    val instanceMessage: InstanceMessage

    override val workflowInfo: WorkflowInfo get() = instanceMessage.workflowInfo

    override val workflowState: WorkflowState get() = instanceMessage.workflowState

    override val parentId get() = instanceMessage.parentId
}

@ExperimentalTime
interface WithOptionalInstanceMessage : WithOptionalWorkflowInfo, WithOptionalWorkflowState {

    val instanceMessage: InstanceMessage?

    override val workflowInfo get() = instanceMessage?.workflowInfo

    override val workflowState get() = instanceMessage?.workflowState

    override val parentId get() = instanceMessage?.parentId
}
