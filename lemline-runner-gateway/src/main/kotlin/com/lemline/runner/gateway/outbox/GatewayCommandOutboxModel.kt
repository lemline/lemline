// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.Instant

data class GatewayCommandOutboxModel(
    override val id: IDV7,
    override var instanceMessage: InstanceMessage<WorkflowCommand.ResumeFromTask>,
    override val outboxScheduledFor: Instant?,
) : WithInstanceMessage, WithId, WithOutbox, WithCleanup {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    override var cleanupAfter: Instant? = null
}
