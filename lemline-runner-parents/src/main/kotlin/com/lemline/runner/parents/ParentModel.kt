// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithCompletedAt
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class ParentModel(
    /** Unique identifier for this parent-child relationship */
    override val id: IDV7 = instanceMessage.workflowState.nodeStack.deriveIdempotentId("-parent"),

    /** Parent workflow state when the child workflow was started */
    override val instanceMessage: InstanceMessage<WorkflowEvent.RunWorkflowStarted>,

    /** The workflow ID of the child workflow that was spawned */
    val childId: WorkflowId = WorkflowId(id.derive("-child"))
) : WithId, WithInstanceMessage, WithCompletedAt, WithCleanup {

    /** Timestamp when the child workflow completed */
    override var completedAt: Instant? = null

    /** Timestamp after which this entity can be deleted, set when child completes */
    override var cleanupAfter: Instant? = null

    // Needed by tests
    companion object Companion
}
