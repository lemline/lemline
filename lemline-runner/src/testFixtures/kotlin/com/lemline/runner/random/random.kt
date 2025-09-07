// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.random.random
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.ingestion.FailureIngestionMessage
import com.lemline.runner.ingestion.ParentIngestionMessage
import com.lemline.runner.ingestion.RetryIngestionMessage
import com.lemline.runner.ingestion.ScheduleIngestionMessage
import com.lemline.runner.ingestion.WaitIngestionMessage
import com.lemline.runner.instances.InstanceMessage
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

fun OutBoxStatus.Companion.random() = Random.nextInt(OutBoxStatus.entries.size).let {
    OutBoxStatus.entries[it]
}

fun WorkflowState.Companion.random(): WorkflowState {
    val randomPos = NodePosition.random()

    return WorkflowState(
        workflowId = WorkflowId.random(),
        workflowName = WorkflowName.random(),
        workflowVersion = WorkflowVersion.random(),
        currentPosition = randomPos,
        currentStates = NodeStates(mapOf(randomPos to NodeState.random())),
    )
}

fun InstanceMessage.Companion.random() = InstanceMessage(
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

fun ParentIngestionMessage.Companion.random() =
    ParentIngestionMessage(ParentOutboxModel.random())

fun ScheduleIngestionMessage.Companion.random() =
    ScheduleIngestionMessage(ScheduleOutboxModel.random())

fun WaitIngestionMessage.Companion.random() =
    WaitIngestionMessage(WaitOutboxModel.random())

fun RetryIngestionMessage.Companion.random() =
    RetryIngestionMessage(RetryOutboxModel.random())

fun FailureIngestionMessage.Companion.random() =
    FailureIngestionMessage(FailureModel.random())

