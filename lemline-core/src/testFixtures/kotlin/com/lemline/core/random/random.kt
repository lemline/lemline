// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.random

import com.lemline.common.random.random
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.DoTaskState
import com.lemline.core.states.ForTaskState
import com.lemline.core.states.RootState
import com.lemline.core.states.SimpleTaskState
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryTaskState
import com.lemline.core.states.WorkflowState
import com.lemline.core.workflows.FlowDirective
import com.lemline.core.workflows.FlowDirectiveEnum
import com.lemline.core.workflows.FlowDirectiveGoto
import kotlin.random.Random
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

fun NodePosition.Companion.random() = NodePosition(listOf(String.random(), String.random(), String.random()))

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    workflowId = WorkflowId.random(),
    workflowNamespace = WorkflowNamespace.random(),
    workflowName = WorkflowName.random(),
    workflowVersion = WorkflowVersion.random(),
)

fun TaskState.Companion.random() = when (Random.nextInt(5)) {
    0 -> DoTaskState.random()
    1 -> ForTaskState.random()
    2 -> SimpleTaskState.random()
    3 -> TryTaskState.random()
    else -> RootState.random()
}

fun DoTaskState.Companion.random() = DoTaskState(
    startedAt = Instant.random(),
    index = Random.nextInt(),
)

fun ForTaskState.Companion.random() = ForTaskState(
    startedAt = Instant.random(),
    collection = listOf(JsonElement.random()),
    index = Random.nextInt(),
).apply {
    forEach = String.random()
    forAt = String.random()
}

fun SimpleTaskState.Companion.random() = SimpleTaskState(
    startedAt = Instant.random(),
)

fun TryTaskState.Companion.random() = TryTaskState(
    startedAt = Instant.random(),
    transformedInput = JsonElement.random(),
    attemptIndex = Random.nextInt(0, 10),
    runningCatch = Random.nextBoolean(),
    lastError = when (Random.nextBoolean()) {
        true -> InternalWorkflowException.Error.random()
        false -> null
    },
    errorAs = "error",
)

fun RootState.Companion.random() = RootState(
    startedAt = Instant.random(),
    id = String.random(),
    input = JsonElement.random(),
    context = buildJsonObject {
        repeat(Random.nextInt(0, 4)) {
            put(String.random(), JsonElement.random())
        }
    },
    hasRun = Random.nextBoolean(),
)

fun InternalWorkflowException.Error.Companion.random() = InternalWorkflowException.Error(
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

fun ChildWorkflowException.Config.Companion.random() = ChildWorkflowException.Config(
    namespace = String.random(),
    name = String.random(),
    version = String.random(),
    input = JsonElement.random(),
    sync = Random.nextBoolean()
)

fun WorkflowState.Companion.random(): WorkflowState {
    return when (Random.nextInt(6)) {
        0 -> WorkflowState.Completed.random()
        1 -> WorkflowState.Failed.random()
        2 -> WorkflowState.ReadyForNextTask.random()
        3 -> WorkflowState.Waiting.random()
        4 -> WorkflowState.WaitingToRetry.random()
        else -> WorkflowState.RunningChildWorkflow.random()
    }
}

fun randomStates() = mapOf(NodePosition.random() to TaskState.random())

fun WorkflowState.Completed.Companion.random() = WorkflowState.Completed(
    output = JsonElement.random()
)

fun WorkflowState.Failed.Companion.random() =
    WorkflowState.Failed(
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
        exception = null
    )

fun WorkflowState.ReadyForNextTask.Companion.random() =
    WorkflowState.ReadyForNextTask(
        taskStates = randomStates(),
        nextNodePosition = NodePosition.random(),
        nextRawInput = JsonElement.random(),
        nextFlowDirective = when (Random.nextBoolean()) {
            true -> randomFlowDirective()
            false -> null
        }
    )

fun WorkflowState.Waiting.Companion.random() =
    WorkflowState.Waiting(
        taskStates = randomStates(),
        nodePosition = NodePosition.random(),
        rawOutput = JsonElement.random(),
        duration = Random.nextLong(100, 10000).milliseconds
    )

fun WorkflowState.WaitingToRetry.Companion.random() =
    WorkflowState.WaitingToRetry(
        taskStates = randomStates(),
        nodePosition = NodePosition.random(),
        rawInput = JsonElement.random(),
        flowDirective = when (Random.nextBoolean()) {
            true -> randomFlowDirective()
            false -> null
        },
        duration = Random.nextLong(100, 10000).milliseconds
    )

fun WorkflowState.RunningChildWorkflow.Companion.random() =
    WorkflowState.RunningChildWorkflow(
        taskStates = randomStates(),
        nodePosition = NodePosition.random(),
        rawOutput = when (Random.nextBoolean()) {
            true -> JsonElement.random()
            false -> null
        },
        childConfig = ChildWorkflowException.Config.random()
    )
