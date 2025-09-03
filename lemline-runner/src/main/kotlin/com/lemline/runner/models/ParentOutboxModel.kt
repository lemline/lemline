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

    override var instanceMessage: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxErrorClass: String? = null,

    override var outboxErrorMessage: String? = null,

    override var outboxErrorStackTrace: String? = null,
) : OutboxModel {

    /**
     * Completes the instance's state by setting the output at the current position.
     * Set also outboxScheduledFor as now to restart this workflow.
     */
    fun completeWith(output: JsonElement) {
        // set the workflow output at the rawOutput at the current position of the workflow
        val updatedStates =
            instanceMessage.workflowInstance.currentStates.apply { this[currentPosition!!]!!.rawOutput = output }
        instanceMessage =
            instanceMessage.copy(workflowInstance = instanceMessage.workflowInstance.copy(currentStates = updatedStates))
        // Set delayedUntil to restart parent workflow via the ParentOutbox
        val now = Clock.System.now()
        outboxScheduledFor = now
        outboxDelayedUntil = now
    }
}
