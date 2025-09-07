// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.random

import com.lemline.common.random.random
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowState
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun NodePosition.Companion.random() = NodePosition(listOf(String.random(), String.random(), String.random()))

fun NodeState.Companion.random() = NodeState(
    attemptIndex = Random.nextInt(),
    childIndex = Random.nextInt(),
    rawInput = JsonPrimitive(String.random()),
    rawOutput = JsonPrimitive(Random.nextInt()),
    context = JsonObject(mapOf(String.random() to JsonPrimitive(String.random()))),
    startedAt = Instant.random(),
    forIndex = Random.nextInt(),
)

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
