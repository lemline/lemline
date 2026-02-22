// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.core.random.randomResumeFromTaskCommand
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.Instant

fun randomGatewayCommandOutboxModel() = GatewayCommandOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomResumeFromTaskCommand(),
    ),
    outboxScheduledFor = Instant.nullableRandom(),
).also { it.randomize(nullableDelayed = true) }

private fun WithOutbox.randomize(nullableDelayed: Boolean = false) = apply {
    outboxDelayedUntil = if (nullableDelayed) Instant.nullableRandom() else Instant.random()
    outboxAttemptCount = Int.random()
    outboxErrorClass = String.nullableRandom()
    outboxErrorMessage = String.nullableRandom()
}
