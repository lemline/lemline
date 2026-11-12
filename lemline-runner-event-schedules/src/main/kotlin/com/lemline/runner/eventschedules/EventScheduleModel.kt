// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.Instant

data class EventScheduleModel(
    /** Unique identifier for this durable event-triggered start */
    override val id: IDV7 = IDV7.random(),

    /** Initial workflow command */
    override val instanceMessage: InstanceMessage<WorkflowCommand.ResumeFromTask>,

    /** Timestamp when the trigger completed */
    override val outboxScheduledFor: Instant = kotlin.time.Clock.System.now()
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null for EventScheduleModel" }
            field = until
        }

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    override var cleanupAfter: Instant? = null

}
