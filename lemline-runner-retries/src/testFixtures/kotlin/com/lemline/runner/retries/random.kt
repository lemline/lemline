// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.retries

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.random
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

fun RetryModel.Companion.random() = RetryModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.TaskRetryScheduled.random(),
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
