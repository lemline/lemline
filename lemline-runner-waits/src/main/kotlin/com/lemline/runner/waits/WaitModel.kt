// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class WaitModel(
    /** Unique identifier for this wait operation - must be derived from position + step for idempotency */
    override val id: IDV7,

    /** Workflow state when the wait was initiated */
    override val instanceMessage: InstanceMessage<WorkflowEvent.WaitStarted>,

    /** Timestamp when the wait period should end and workflow should resume */
    override val outboxScheduledFor: Instant,
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

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

    override var cleanupAfter: Instant? = null

    // Needed by tests
    companion object Companion
}
