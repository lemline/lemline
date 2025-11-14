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
import com.lemline.core.orchestrator.WorkflowState
import com.lemline.core.states.DoState
import com.lemline.core.states.ForState
import com.lemline.core.states.NodeState
import com.lemline.core.states.PositionStates
import com.lemline.core.states.RootState
import com.lemline.core.states.SimpleState
import com.lemline.core.states.TryState
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

fun PositionStates.Companion.random() = PositionStates(
    mapOf(
        NodePosition.random() to NodeState.random(),
        NodePosition.random() to NodeState.random(),
        NodePosition.random() to NodeState.random(),
    )
)

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    workflowId = WorkflowId.random(),
    workflowNamespace = WorkflowNamespace.random(),
    workflowName = WorkflowName.random(),
    workflowVersion = WorkflowVersion.random(),
)

fun NodeState.Companion.random() = when (Random.nextInt(5)) {
    0 -> DoState.random()
    1 -> ForState.random()
    2 -> SimpleState.random()
    3 -> TryState.random()
    else -> RootState.random()
}

fun DoState.Companion.random() = DoState(
    startedAt = Instant.random(),
    index = Random.nextInt(),
)

fun ForState.Companion.random() = ForState(
    startedAt = Instant.random(),
    collection = listOf(JsonElement.random()),
    index = Random.nextInt(),
).apply {
    forEach = String.random()
    forAt = String.random()
}

fun SimpleState.Companion.random() = SimpleState(
    startedAt = Instant.random(),
)

fun TryState.Companion.random() = TryState(
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

fun randomStates() = mapOf(NodePosition.random() to NodeState.random())

fun WorkflowState.Completed.Companion.random() = WorkflowState.Completed(
    output = JsonElement.random()
)

fun WorkflowState.Failed.Companion.random() =
    WorkflowState.Failed(
        states = randomStates(),
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
        states = randomStates(),
        nextNodePosition = NodePosition.random(),
        nextRawInput = JsonElement.random(),
        nextFlowDirective = when (Random.nextBoolean()) {
            true -> randomFlowDirective()
            false -> null
        }
    )

fun WorkflowState.Waiting.Companion.random() =
    WorkflowState.Waiting(
        states = randomStates(),
        nodePosition = NodePosition.random(),
        rawOutput = JsonElement.random(),
        duration = Random.nextLong(100, 10000).milliseconds
    )

fun WorkflowState.WaitingToRetry.Companion.random() =
    WorkflowState.WaitingToRetry(
        states = randomStates(),
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
        states = randomStates(),
        nodePosition = NodePosition.random(),
        rawOutput = when (Random.nextBoolean()) {
            true -> JsonElement.random()
            false -> null
        },
        childConfig = ChildWorkflowException.Config.random()
    )
