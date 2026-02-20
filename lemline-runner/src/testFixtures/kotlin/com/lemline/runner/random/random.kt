// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.random

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.random.randomWorkflowName
import com.lemline.common.random.randomWorkflowNamespace
import com.lemline.common.random.randomWorkflowVersion
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.random.*
import com.lemline.core.random.randomForkStartedEvent
import com.lemline.core.random.randomListenStartedEvent
import com.lemline.core.random.randomRunWorkflowConfig
import com.lemline.core.random.randomWaitConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
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

private fun NodeStack.Companion.random(): NodeStack = NodeStack.fromFrames(
    listOf(
        StackFrame(
            NodePosition.root, RootState(
                startedAt = Clock.System.now(),
                workflowId = WorkflowId.random(),
                workflowInput = JsonElement.random()
            )
        )
    )
)

fun randomWorkflowInfo() = WorkflowInfo(
    namespace = randomWorkflowNamespace(),
    name = randomWorkflowName(),
    version = randomWorkflowVersion()
)

fun randomWorkflowCommand() = when (Random.nextBoolean()) {
    true -> randomResumeFromTaskCommand()
    false -> randomResumeWithCompletedTaskCommand()
}

fun randomResumeFromTaskCommand() = WorkflowCommand.ResumeFromTask(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null
)

fun randomResumeWithCompletedTaskCommand() = WorkflowCommand.ResumeWithCompletedTask(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
)

fun randomWorkflowFailedEvent() = WorkflowEvent.WorkflowFailed(
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

fun randomRunWorkflowStartedEvent() = WorkflowEvent.RunWorkflowStarted(
    nodeStack = NodeStack.random(),
    rawInput = JsonElement.random(),
    config = randomRunWorkflowConfig()
)

fun randomTaskRetryScheduledEvent() = WorkflowEvent.TaskRetryScheduled(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = null,
    retryAt = Clock.System.now()
)

fun randomWaitStartedEvent() = WorkflowEvent.WaitStarted(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
    config = randomWaitConfig()
)

fun randomWorkflowState(): WorkflowState = randomWorkflowCommand()

fun InstanceMessage.Companion.random() = InstanceMessage(
    workflowInfo = randomWorkflowInfo(),
    workflowState = randomWorkflowState(),
)

fun InstanceMessage.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

fun FailureModel.Companion.random() = FailureModel(
    id = IDV7.random(),
    instanceMessage = when (Random.nextBoolean()) {
        true -> InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomWorkflowFailedEvent(),
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
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomRunWorkflowStartedEvent(),
    ),
    childId = WorkflowId.random()
)

fun ScheduleModel.Companion.random() = ScheduleModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomResumeFromTaskCommand(),
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
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomTaskRetryScheduledEvent(),
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
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomWaitStartedEvent(),
    ),
    outboxScheduledFor = Instant.random(),
).also {
    // Don't call randomize() as outboxDelayedUntil cannot be null for WaitOutboxModel
    it.outboxAttemptCount = Int.random()
    it.outboxErrorClass = String.nullableRandom()
    it.outboxErrorMessage = String.nullableRandom()
}

fun randomForkModel() = ForkModel(
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomForkStartedEvent(),
    ),
    compete = Random.nextBoolean(),
    id = IDV7.random(),
    position = NodePosition.random().toString()
)

fun randomForkBranchModel(forkId: IDV7 = IDV7.random()) = ForkBranchModel(
    forkId = forkId,
    branchPosition = String.random()
)

fun randomListenerEventModel(
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
    val workflowInfo = randomWorkflowInfo()
    val listenStarted = randomListenStartedEvent()
    val config = listenStarted.config

    return ListenerModel(
        instanceMessage = InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = listenStarted,
        ),
        id = IDV7.random(),
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
