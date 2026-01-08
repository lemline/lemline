// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.models

import com.lemline.core.states.WorkflowState
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithInstanceMessage {
    /** The current execution state of the workflow instance, null if not available */
    val instanceMessage: InstanceMessage<out WorkflowState>

    val workflowInfo get() = instanceMessage.workflowInfo

    val workflowState get() = instanceMessage.workflowState

    val workflowId get() = instanceMessage.workflowState.workflowId

    val workflowNamespace get() = instanceMessage.workflowInfo.namespace

    val workflowName get() = instanceMessage.workflowInfo.name

    val workflowVersion get() = instanceMessage.workflowInfo.version

    val nodePosition get() = instanceMessage.workflowState.nodePosition
}
