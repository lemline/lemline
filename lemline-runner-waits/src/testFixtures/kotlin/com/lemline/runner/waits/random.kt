// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.core.random.randomWaitStartedEvent
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.time.Instant

fun WaitModel.Companion.random() = WaitModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomWaitStartedEvent(),
    ),
    outboxScheduledFor = Instant.random(),
).also {
    // Don't call randomize() as outboxDelayedUntil cannot be null for WaitModel
    it.outboxAttemptCount = Int.random()
    it.outboxErrorClass = String.nullableRandom()
    it.outboxErrorMessage = String.nullableRandom()
}
