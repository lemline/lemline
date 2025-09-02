// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.ids.IdGenerator
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
data class ParentOutboxModel(
    override val id: UUID = IdGenerator.generateUUIDV7(),

    override var instance: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxLastError: String? = null,
) : OutboxModel {

    /**
     * Completes the instance's state by setting the output at the current position.
     * Set also outboxScheduledFor as now to restart this workflow.
     */
    fun completeWith(output: JsonElement) {
        // set the workflow output at the rawOutput at the current position of the workflow
        val state = workflowState.apply { this[workflowPosition]!!.rawOutput = output }
        instance = instance.copy(workflowState = LazyParsedField(state, WorkflowState.serializer()))
        // Set delayedUntil to restart parent workflow via the ParentOutbox
        val now = Clock.System.now()
        outboxScheduledFor = now
        outboxDelayedUntil = now
    }
}
