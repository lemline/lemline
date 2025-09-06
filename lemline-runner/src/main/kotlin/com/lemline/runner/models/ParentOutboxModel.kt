// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
data class ParentOutboxModel(
    override val id: IDV7,

    override val instanceMessage: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxErrorClass: String? = null,

    override var outboxErrorMessage: String? = null,

    override var outboxErrorStackTrace: String? = null,
) : OutboxModel(instanceMessage) {

    /**
     * Completes the instance's state by setting the output at the current position.
     * Set also outboxScheduledFor as now to restart this workflow.
     */
    fun completeWith(output: JsonElement) {
        // Update the workflow state with the output
        instanceMessage.workflowState.setCurrentTaskOutput(output)
        // Set to restart parent workflow via the ParentOutbox
        val now = Clock.System.now()
        outboxScheduledFor = now
        outboxDelayedUntil = now
    }
}
