// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.WaitConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithOutbox
import com.lemline.runner.failures.FailureModel
import com.lemline.runner.forks.ForkBranchModel
import com.lemline.runner.forks.ForkModel
import com.lemline.runner.listeners.ListenerEventModel
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerStrategy
import com.lemline.runner.parents.ParentModel
import com.lemline.runner.retries.RetryModel
import com.lemline.runner.schedules.ScheduleModel
import com.lemline.runner.waits.WaitModel
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

// Helper function to create state stack with RootState
private fun NodeStack.Companion.random(): NodeStack = NodeStack(
    listOf(
        NodePosition.root to RootState(
            startedAt = Clock.System.now(),
            workflowId = WorkflowId.random(),
            workflowInput = JsonElement.random()
        )
    )
)

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    namespace = WorkflowNamespace.random(),
    name = WorkflowName.random(),
    version = WorkflowVersion.random()
)

fun WorkflowCommand.Companion.random() = when (Random.nextBoolean()) {
    true -> WorkflowCommand.ResumeFromTask.random()
    false -> WorkflowCommand.ResumeWithCompletedTask.random()
}

fun WorkflowCommand.ResumeFromTask.Companion.random() = WorkflowCommand.ResumeFromTask(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null
)

fun WorkflowCommand.ResumeWithCompletedTask.Companion.random() = WorkflowCommand.ResumeWithCompletedTask(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
)

fun WorkflowEvent.WorkflowFailed.Companion.random() = WorkflowEvent.WorkflowFailed(
    nodeStack = NodeStack.random(),
    rawInput = JsonElement.random(),
    rawOutput = JsonElement.random(),
    flowDirective = null,
    error = InternalException.Error(
        type = "TestError",
        status = 500,
        position = "test-instance",
        title = "Test error",
        details = "Test error details"
    ),
    failedAt = Clock.System.now()
)

fun WorkflowEvent.RunWorkflowStarted.Companion.random() = WorkflowEvent.RunWorkflowStarted(
    nodeStack = NodeStack.random(),
    rawInput = JsonElement.random(),
    config = RunWorkflowConfig.random()
)

fun WorkflowEvent.TaskRetryScheduled.Companion.random() = WorkflowEvent.TaskRetryScheduled(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null,
    retryAt = Clock.System.now()
)

fun WorkflowEvent.WaitStarted.Companion.random() = WorkflowEvent.WaitStarted(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
    config = WaitConfig.random()
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
            workflowState = WorkflowEvent.WorkflowFailed.random(),
        )

        false -> null
    },
    payload = String.nullableRandom(),
    errorReason = String.random(),
    errorClass = String.random(),
    errorMessage = String.nullableRandom(),
    errorStackTrace = String.random(),
)

fun WithOutbox.randomize(nullableDelayed: Boolean = false) = apply {
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
    childId = WorkflowId.random()
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
        workflowState = WorkflowEvent.TaskRetryScheduled.random(),
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

fun ForkModel.Companion.random() = ForkModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = WorkflowInfo.random(),
        workflowState = WorkflowEvent.ForkStarted.random(),
    ),
    position = NodePosition.random().toString(),
    compete = Random.nextBoolean()
)

fun ForkBranchModel.Companion.random(forkId: IDV7 = IDV7.random()) = ForkBranchModel(
    forkId = forkId,
    name = String.random(),
    output = null,
    completedAt = null,
    failedAt = null
)

fun ListenerEventModel.Companion.random(
    listenerId: IDV7 = IDV7.random(),
    filterIndex: Int = Random.nextInt(0, 10)
) = ListenerEventModel(
    listenerId = listenerId,
    eventId = IDV7.random().toString(),
    filterIndex = filterIndex,
    event = """{"type":"com.example.${String.random()}","data":{"value":${Random.nextInt()}}}""",
    outboxScheduledFor = Instant.random()
)

fun ListenerModel.Companion.random(): ListenerModel {
    val workflowInfo = WorkflowInfo.random()
    val listenStarted = WorkflowEvent.ListenStarted.random()
    val config = listenStarted.config

    return ListenerModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = listenStarted,
        ),
        listenerStrategy = ListenerStrategy.from(config),
        timeoutAt = config.timeoutAt,
    ).also {
        // Don't call randomize() as outboxDelayedUntil starts as null for ListenerModel
        it.outboxScheduledFor = Instant.random()
        it.outboxAttemptCount = Int.random()
        it.outboxErrorClass = String.nullableRandom()
        it.outboxErrorMessage = String.nullableRandom()
        it.correlationValues = when (Random.nextBoolean()) {
            true -> """{"${String.random()}":"${String.random()}"}"""
            false -> null
        }
        it.hasUntil = Random.nextBoolean()
        it.hasForeach = Random.nextBoolean()
    }
}
