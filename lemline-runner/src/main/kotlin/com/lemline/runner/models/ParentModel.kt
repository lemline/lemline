// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class ParentModel(
    override val id: IDV7,
    override val instanceMessage: InstanceMessage<WorkflowEvent.RunWorkflowStarted>,
    val childId: IDV7,
    override var outboxCompletedAt: Instant? = null
) : CleanableModel() {

    // Needed by tests
    companion object Companion
}
