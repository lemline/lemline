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
data class WaitModel(
    override val id: IDV7 = IDV7.random(),
    override val instanceMessage: InstanceMessage<WorkflowEvent.WaitStarted>,
    override val outboxScheduledFor: Instant,
) : OutboxModel() {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null for WaitOutboxModel" }
            field = until
        }

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    // Needed by tests
    companion object Companion
}
