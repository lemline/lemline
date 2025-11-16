// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.nodes.NodePosition
import com.lemline.core.random.random
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

fun OutBoxStatus.Companion.random() = Random.nextInt(OutBoxStatus.entries.size).let {
    OutBoxStatus.entries[it]
}

fun WorkflowState.Companion.random(): WorkflowState {
    // Generate a simple random state for testing (ReadyForNextTask)
    // Could be extended to randomly choose between different state variants if needed
    return WorkflowState.ReadyForNextTask(
        taskStates = emptyMap(),
        nodePosition = NodePosition.random(),
        rawInput = JsonElement.random(),
        flowDirective = null
    )
}

fun InstanceMessage.Companion.random() = InstanceMessage(
    workflowInfo = WorkflowInfo.random(),
    workflowState = WorkflowState.random(),
    parentId = IDV7.random()
)

fun InstanceMessage.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

fun FailureModel.Companion.random() = FailureModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.nullableRandom(),
    payload = String.nullableRandom(),
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
)

fun OutboxModel.randomize(nullableDelayed: Boolean = false) = apply {
    outboxDelayedUntil = if (nullableDelayed) Instant.nullableRandom() else Instant.random()
    outboxAttemptCount = Int.random()
    outboxErrorClass = String.nullableRandom()
    outboxErrorMessage = String.nullableRandom()
}

fun ParentOutboxModel.Companion.random() = ParentOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.nullableRandom(), // <- Nullable
).also { it.randomize(nullableDelayed = true) }

fun ScheduleOutboxModel.Companion.random() = ScheduleOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.nullableRandom(), // <- nullable
    scheduleAfter = String.nullableRandom(),
    scheduleEvery = String.nullableRandom(),
    scheduleCron = String.nullableRandom(),
    scheduleZone = String.nullableRandom(),
).also { it.randomize(nullableDelayed = true) }

fun RetryOutboxModel.Companion.random() = RetryOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.random(), // <- Not nullable
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
).also { it.randomize() }

fun WaitOutboxModel.Companion.random() = WaitOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.random(), // <- Not nullable
).also { it.randomize() }
