// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.RunWorkflowException
import com.lemline.core.nodes.NodePosition
import com.lemline.core.random.random
import com.lemline.core.states.RootState
import com.lemline.core.states.RunState
import com.lemline.core.states.TaskStates
import com.lemline.core.states.WaitState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.models.WaitModel
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

// Helper function to create task states with RootState
private fun randomTaskStates(): TaskStates = mapOf(
    NodePosition.root to RootState(
        startedAt = Clock.System.now(),
        workflowId = WorkflowId.random(),
        workflowInput = JsonElement.random()
    )
)

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    workflowNamespace = WorkflowNamespace.random(),
    workflowName = WorkflowName.random(),
    workflowVersion = WorkflowVersion.random()
)

fun WorkflowCommand.Companion.random() = when (Random.nextBoolean()) {
    true -> WorkflowCommand.ResumeFromTask.random()
    false -> WorkflowCommand.ResumeFromStartedTask.random()
}

fun WorkflowCommand.ResumeFromTask.Companion.random() = WorkflowCommand.ResumeFromTask(
    taskStates = randomTaskStates(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null
)

fun WorkflowCommand.ResumeFromStartedTask.Companion.random() = WorkflowCommand.ResumeFromStartedTask(
    taskStates = randomTaskStates(),
    nodePosition = NodePosition.random(),
    rawOutput = JsonElement.random(),
)

fun WorkflowEvent.TaskFailed.Companion.random() = WorkflowEvent.TaskFailed(
    taskStates = randomTaskStates(),
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
    taskStates = randomTaskStates(),
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
    taskStates = randomTaskStates(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null,
    retryAt = Clock.System.now()
)

fun WorkflowEvent.WaitStarted.Companion.random() = WorkflowEvent.WaitStarted(
    taskStates = randomTaskStates(),
    nodePosition = NodePosition.random(),
    waitState = WaitState(),
    rawOutput = JsonElement.random(),
    waitUntil = Clock.System.now()
)

fun WorkflowState.Companion.random(): WorkflowState = WorkflowCommand.random()

fun InstanceMessage.Companion.random() = InstanceMessage(
    workflowInfo = WorkflowInfo.random(),
    workflowState = WorkflowState.random(),
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

fun ParentModel.Companion.random() = ParentModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.RunWorkflowStarted.random(),
    ),
    childId = IDV7.random()
)

fun ScheduleModel.Companion.random() = ScheduleModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowCommand.ResumeFromTask.random(),
    ),
    outboxScheduledFor = Instant.nullableRandom(),
    scheduleAfter = String.nullableRandom(),
    scheduleEvery = String.nullableRandom(),
    scheduleCron = String.nullableRandom(),
    scheduleZone = String.nullableRandom(),
).also { it.randomize(nullableDelayed = true) }

fun RetryModel.Companion.random() = RetryModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.RetryScheduled.random(),
    ),
    outboxScheduledFor = Instant.random(),
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
).also {
    // Don't call randomize() as outboxDelayedUntil cannot be null for RetryOutboxModel
    it.outboxAttemptCount = Int.random()
    it.outboxErrorClass = String.nullableRandom()
    it.outboxErrorMessage = String.nullableRandom()
}

fun WaitModel.Companion.random() = WaitModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.WaitStarted.random(),
    ),
    outboxScheduledFor = Instant.random(),
).also {
    // Don't call randomize() as outboxDelayedUntil cannot be null for WaitOutboxModel
    it.outboxAttemptCount = Int.random()
    it.outboxErrorClass = String.nullableRandom()
    it.outboxErrorMessage = String.nullableRandom()
}
