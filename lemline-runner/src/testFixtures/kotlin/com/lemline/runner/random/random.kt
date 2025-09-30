// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.random.random
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.ingestion.WorkflowCompletedMessage
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.models.bases.OptionalOutboxModel
import com.lemline.runner.models.bases.OutboxModel
import com.lemline.runner.outbox.bases.RunStatus
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

fun RunStatus.Companion.random() = Random.nextInt(RunStatus.entries.size).let {
    RunStatus.entries[it]
}

fun WorkflowState.Companion.random(): WorkflowState {
    val randomPos = NodePosition.random()

    return WorkflowState(
        currentPosition = randomPos,
        currentStates = NodeStates(mapOf(randomPos to NodeState.random())),
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

fun OptionalOutboxModel.randomize() = apply {
    runDelayedUntil = Instant.nullableRandom()
    runAttemptCount = Int.random()
    runLastErrorClass = String.nullableRandom()
    runLastErrorMessage = String.nullableRandom()
}

fun OutboxModel.randomize() = apply {
    runDelayedUntil = Instant.random()
    runAttemptCount = Int.random()
    runLastErrorClass = String.nullableRandom()
    runLastErrorMessage = String.nullableRandom()
}

fun ParentModel.Companion.random() = ParentModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    runStatus = RunStatus.random(),
    runAt = Instant.nullableRandom(), // <- Nullable
)

fun ForkModel.Companion.random() = ForkModel(
    id = IDV7.random(),
    workflowInfo = WorkflowInfo.random(),
    forkId = IDV7.random(),
    forkPosition = NodePosition.random(),
    forkName = String.random(),
    forkOutput = String.nullableRandom(),
    runStatus = RunStatus.random(),
    runAt = Instant.nullableRandom(), // <- Nullable
)

fun ScheduleModel.Companion.random() = ScheduleModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    runStatus = RunStatus.random(),
    runAt = Instant.nullableRandom(), // <- nullable
    scheduleAfter = String.nullableRandom(),
    scheduleEvery = String.nullableRandom(),
    scheduleCron = String.nullableRandom(),
    scheduleZone = String.nullableRandom(),
).also { it.randomize() }

fun RetryModel.Companion.random() = RetryModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    runStatus = RunStatus.random(),
    runAt = Instant.random(), // <- Not nullable
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
).also { it.randomize() }

fun WaitModel.Companion.random() = WaitModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage.random(),
    runStatus = RunStatus.random(),
    runAt = Instant.random(), // <- Not nullable
).also { it.randomize() }


fun WorkflowCompletedMessage.Companion.random(): WorkflowCompletedMessage {
    val parentId = IDV7.nullableRandom()

    return WorkflowCompletedMessage(
        workflowInfo = WorkflowInfo.random(),
        parentId = parentId,
        output = if (parentId == null) null else JsonElement.random(),
        isScheduledAfter = Random.nextBoolean(),
    )
}
