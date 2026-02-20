// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.core.random.randomTaskRetryScheduledEvent
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.time.Instant

fun RetryModel.Companion.random() = RetryModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomTaskRetryScheduledEvent(),
    ),
    outboxScheduledFor = Instant.random(),
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
).also {
    // Don't call randomize() as outboxDelayedUntil cannot be null for RetryModel
    it.outboxAttemptCount = Int.random()
    it.outboxErrorClass = String.nullableRandom()
    it.outboxErrorMessage = String.nullableRandom()
}
