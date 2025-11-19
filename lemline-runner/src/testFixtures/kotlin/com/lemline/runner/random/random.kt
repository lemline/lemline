// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.RunWorkflowException
import com.lemline.core.nodes.NodePosition
import com.lemline.core.random.random
import com.lemline.core.states.RunState
import com.lemline.core.states.WaitState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.models.ParentWaitingModel
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

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    workflowId = com.lemline.common.values.WorkflowId.random(),
    workflowNamespace = WorkflowNamespace("test"),
    workflowName = WorkflowName("test-workflow"),
    workflowVersion = WorkflowVersion("1.0")
)

fun WorkflowCommand.Companion.random(): WorkflowCommand = WorkflowCommand.ResumeFromTask(
    taskStates = emptyMap<NodePosition, com.lemline.core.states.TaskState>(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null
)

fun WorkflowEvent.TaskFailed.Companion.random() = WorkflowEvent.TaskFailed(
    taskStates = emptyMap<NodePosition, com.lemline.core.states.TaskState>(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    rawOutput = JsonElement.random(),
    flowDirective = null,
    error = InternalException.Error(
        type = "TestError",
        status = 500,
        instance = "test-instance",
        title = "Test error",
        details = "Test error details"
    )
)

fun WorkflowEvent.RunWorkflowStarted.Companion.random() = WorkflowEvent.RunWorkflowStarted(
    taskStates = emptyMap<NodePosition, com.lemline.core.states.TaskState>(),
    nodePosition = NodePosition.random(),
    runState = RunState(),
    rawInput = JsonElement.random(),
    childConfig = RunWorkflowException.Config(
        namespace = WorkflowNamespace("test"),
        name = WorkflowName("test"),
        version = WorkflowVersion("1.0"),
        input = JsonElement.random(),
        sync = true
    )
)

fun WorkflowEvent.RetryScheduled.Companion.random() = WorkflowEvent.RetryScheduled(
    taskStates = emptyMap<NodePosition, com.lemline.core.states.TaskState>(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null,
    retryAt = kotlin.time.Clock.System.now()
)

fun WorkflowEvent.WaitStarted.Companion.random() = WorkflowEvent.WaitStarted(
    taskStates = emptyMap<NodePosition, com.lemline.core.states.TaskState>(),
    nodePosition = NodePosition.random(),
    waitState = WaitState(),
    rawOutput = JsonElement.random(),
    waitUntil = kotlin.time.Clock.System.now()
)

fun WorkflowState.Companion.random(): WorkflowState = WorkflowCommand.random()

fun InstanceMessage.Companion.random() = InstanceMessage(
    workflowInfo = WorkflowInfo.random(),
    workflowState = WorkflowState.random(),
    hasParentWaiting = Random.nextBoolean()
)

fun InstanceMessage.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

fun FailureModel.Companion.random() = FailureModel(
    id = IDV7.random(),
    instanceMessage = when (Random.nextBoolean()) {
        true -> InstanceMessage(
            workflowInfo = WorkflowInfo.random(),
            workflowState = WorkflowEvent.TaskFailed.random(),
            hasParentWaiting = Random.nextBoolean()
        )

        false -> null
    },
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

fun ParentWaitingModel.Companion.random() = ParentWaitingModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.RunWorkflowStarted.random(),
        hasParentWaiting = Random.nextBoolean()
    ),
    childId = IDV7.random()
)

fun ScheduleOutboxModel.Companion.random() = ScheduleOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowCommand.random(),
        hasParentWaiting = Random.nextBoolean()
    ),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.nullableRandom(), // <- nullable
    scheduleAfter = String.nullableRandom(),
    scheduleEvery = String.nullableRandom(),
    scheduleCron = String.nullableRandom(),
    scheduleZone = String.nullableRandom(),
).also { it.randomize(nullableDelayed = true) }

fun RetryOutboxModel.Companion.random() = RetryOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.RetryScheduled.random(),
        hasParentWaiting = Random.nextBoolean()
    ),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.random(), // <- Not nullable
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
).also { it.randomize() }

fun WaitOutboxModel.Companion.random() = WaitOutboxModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.WaitStarted.random(),
        hasParentWaiting = Random.nextBoolean()
    ),
    outBoxStatus = OutBoxStatus.random(),
    outboxScheduledFor = Instant.random(), // <- Not nullable
).also { it.randomize() }
