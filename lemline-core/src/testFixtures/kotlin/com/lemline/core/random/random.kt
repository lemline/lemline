// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.random

import com.lemline.common.random.random
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.AsyncTaskException.RunWorkflowStartedException
import com.lemline.core.errors.InternalException
import com.lemline.common.values.NodePosition
import com.lemline.core.states.CallState
import com.lemline.core.states.DoState
import com.lemline.core.states.ForState
import com.lemline.core.states.ForkState
import com.lemline.core.states.RaiseState
import com.lemline.core.states.RootState
import com.lemline.core.states.RunState
import com.lemline.core.states.SetState
import com.lemline.core.states.SwitchState
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryState
import com.lemline.core.states.WaitState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.workflows.FlowDirective
import com.lemline.core.workflows.FlowDirectiveEnum
import com.lemline.core.workflows.FlowDirectiveGoto
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

fun JsonElement.Companion.random(): JsonElement {
    return when (Random.nextInt(5)) {
        0 -> JsonPrimitive(Random.nextBoolean())
        1 -> JsonPrimitive(Random.nextInt())
        2 -> JsonPrimitive(Random.nextDouble())
        3 -> JsonPrimitive(String.random())
        else -> when (Random.nextBoolean()) {
            true -> buildJsonArray {
                repeat(Random.nextInt(1, 4)) {
                    add(JsonElement.random())
                }
            }

            false -> buildJsonObject {
                repeat(Random.nextInt(1, 4)) {
                    put(String.random(), JsonElement.random())
                }
            }
        }
    }
}

fun JsonElement.Companion.nullableRandom(): JsonElement {
    return when (Random.nextBoolean()) {
        true -> JsonNull
        false -> random()
    }
}

fun NodePosition.Companion.random() = NodePosition.parse("/${String.random()}/${String.random()}/${String.random()}")

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    workflowNamespace = WorkflowNamespace.random(),
    workflowName = WorkflowName.random(),
    workflowVersion = WorkflowVersion.random(),
)

fun TaskState.Companion.random() = when (Random.nextInt(12)) {
    0 -> CallState.random()
    1 -> DoState.random()
    2 -> ForkState.random()
    3 -> ForState.random()
    4 -> RaiseState.random()
    5 -> RootState.random()
    6 -> RunState.random()
    7 -> SetState.random()
    8 -> SwitchState.random()
    9 -> TryState.random()
    10 -> WaitState.random()
    else -> RootState.random()
}

fun CallState.Companion.random() = CallState(
    startedAt = Instant.random(),
)

fun DoState.Companion.random() = DoState(
    startedAt = Instant.random(),
    index = Random.nextInt(),
)

fun ForkState.Companion.random() = ForkState(
    startedAt = Instant.random(),
)

fun ForState.Companion.random() = ForState(
    startedAt = Instant.random(),
    collection = listOf(JsonElement.random()),
    index = Random.nextInt(),
    forEach = String.random(),
    forAt = String.random()
)

fun RaiseState.Companion.random() = RaiseState(
    startedAt = Instant.random(),
)

fun RootState.Companion.random() = RootState(
    startedAt = Instant.random(),
    workflowId = WorkflowId.random(),
    workflowInput = JsonElement.random(),
    context = buildJsonObject {
        repeat(Random.nextInt(0, 4)) {
            put(String.random(), JsonElement.random())
        }
    },
    hasWaitingParent = Random.nextBoolean()
)

fun RunState.Companion.random() = RunState(
    startedAt = Instant.random(),
)

fun SwitchState.Companion.random() = SwitchState(
    startedAt = Instant.random(),
)

fun WaitState.Companion.random() = WaitState(
    startedAt = Instant.random(),
)

fun TryState.Companion.random() = TryState(
    startedAt = Instant.random(),
    transformedInput = JsonElement.random(),
    attemptIndex = Random.nextInt(0, 10),
    runningCatch = Random.nextBoolean(),
    lastError = when (Random.nextBoolean()) {
        true -> InternalException.Error.random()
        false -> null
    },
    errorAs = String.random(),
)

fun SetState.Companion.random() = SetState(
    startedAt = Instant.random(),
)

fun InternalException.Error.Companion.random() = InternalException.Error(
    type = String.random(),
    status = Random.nextInt(400, 600),
    instance = String.random(),
    title = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
    details = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
)

// Random generators for orchestrator.WorkflowState

fun randomFlowDirective(): FlowDirective = when (Random.nextInt(4)) {
    0 -> FlowDirectiveEnum.Continue
    1 -> FlowDirectiveEnum.Exit
    2 -> FlowDirectiveEnum.End
    else -> FlowDirectiveGoto(String.random())
}

fun RunWorkflowStartedException.Config.Companion.random() = RunWorkflowStartedException.Config(
    namespace = WorkflowNamespace.random(),
    name = WorkflowName.random(),
    version = WorkflowVersion.random(),
    input = JsonElement.random(),
    sync = Random.nextBoolean()
)

fun WorkflowState.Companion.random(): WorkflowState = when (Random.nextBoolean()) {
    true -> WorkflowEvent.random()
    false -> WorkflowCommand.random()
}

fun WorkflowCommand.Companion.random(): WorkflowCommand {
    return when (Random.nextInt(2)) {
        0 -> WorkflowCommand.ResumeFromTask.random()
        else -> WorkflowCommand.ResumeWithCompletedTask.random()
    }
}

fun WorkflowCommand.ResumeFromTask.Companion.random() = WorkflowCommand.ResumeFromTask(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = randomFlowDirective(),
)

fun WorkflowCommand.ResumeWithCompletedTask.Companion.random() = WorkflowCommand.ResumeWithCompletedTask(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    rawOutput = JsonElement.random(),
)

fun WorkflowEvent.Companion.random(): WorkflowEvent {
    return when (Random.nextInt(8)) {
        0 -> WorkflowEvent.WorkflowCompleted.random()
        1 -> WorkflowEvent.WorkflowFailed.random()
        2 -> WorkflowEvent.TaskScheduled.random()
        3 -> WorkflowEvent.WaitStarted.random()
        4 -> WorkflowEvent.RetryScheduled.random()
        5 -> WorkflowEvent.RunWorkflowStarted.random()
        6 -> WorkflowEvent.ForkStarted.random()
        else -> WorkflowEvent.BranchCompleted.random()
    }
}

fun randomStates() = mapOf(
    NodePosition.root to RootState.random(),
    NodePosition.random() to TaskState.random()
)

fun WorkflowEvent.WorkflowCompleted.Companion.random() = WorkflowEvent.WorkflowCompleted(
    output = JsonElement.random(),
    completedAt = Clock.System.now(),
    taskStates = randomStates()
)

fun WorkflowEvent.WorkflowFailed.Companion.random() = WorkflowEvent.WorkflowFailed(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    rawInput = when (Random.nextBoolean()) {
        true -> JsonElement.random()
        false -> null
    },
    rawOutput = when (Random.nextBoolean()) {
        true -> JsonElement.random()
        false -> null
    },
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    },
    error = InternalException.Error.random(),
    failedAt = Instant.random()
)

fun WorkflowEvent.TaskScheduled.Companion.random() = WorkflowEvent.TaskScheduled(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    }
)

fun WorkflowEvent.WaitStarted.Companion.random() = WorkflowEvent.WaitStarted(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    waitState = WaitState.random(),
    rawOutput = JsonElement.random(),
    waitUntil = Clock.System.now() + Random.nextLong(100, 10000).milliseconds
)

fun WorkflowEvent.RetryScheduled.Companion.random() = WorkflowEvent.RetryScheduled(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    },
    retryAt = Clock.System.now() + Random.nextLong(100, 10000).milliseconds
)

fun WorkflowEvent.RunWorkflowStarted.Companion.random() = WorkflowEvent.RunWorkflowStarted(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    runState = RunState.random(),
    rawInput = JsonElement.random(),
    childConfig = RunWorkflowStartedException.Config.random()
)

fun WorkflowEvent.ForkStarted.Companion.random() = WorkflowEvent.ForkStarted(
    taskStates = randomStates(),
    nodePosition = NodePosition.random(),
    forkState = ForkState.random(),
    rawInput = JsonElement.random(),
)

fun WorkflowEvent.BranchCompleted.Companion.random() =
    WorkflowEvent.BranchCompleted(
        taskStates = randomStates(),
        nodePosition = NodePosition.random(),
        branchName = String.random(),
        output = JsonElement.random(),
        completedAt = Clock.System.now(),
        flowDirective = when (Random.nextBoolean()) {
            true -> randomFlowDirective()
            false -> null
        }
    )
