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
data class WaitOutboxModel(
    override val id: IDV7 = IDV7.random(),
    override val instanceMessage: InstanceMessage<WorkflowEvent.WaitStarted>,
    val scheduledFor: Instant,
    override var outboxCompletedAt: Instant? = null
) : OutboxModel() {

    override var outboxScheduledFor: Instant? = scheduledFor

    override var outboxDelayedUntil: Instant? = scheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null for WaitOutboxModel" }
            field = until
        }

    override var outboxAttemptCount: Int = 0

    override var outboxFailedAt: Instant? = null

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    // Needed by tests
    companion object
}
