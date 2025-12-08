// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
interface WithInstanceMessage {
    /** The current execution state of the workflow instance, null if not available */
    val instanceMessage: InstanceMessage<out WorkflowState>

    val workflowInfo get() = instanceMessage.workflowInfo

    val workflowState get() = instanceMessage.workflowState

    val workflowId get() = instanceMessage.workflowState.workflowId

    val workflowNamespace get() = instanceMessage.workflowInfo.workflowNamespace

    val workflowName get() = instanceMessage.workflowInfo.workflowName

    val workflowVersion get() = instanceMessage.workflowInfo.workflowVersion

    val nodePosition get() = instanceMessage.workflowState.nodePosition
}
