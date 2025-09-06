// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.random

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowState
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun String.Companion.random(size: Int = 5) = Random.nextBytes(size).toString()

internal fun String.Companion.nullableRandom(size: Int = 5) = when (Random.nextBoolean()) {
    true -> String.random(size)
    false -> null
}

internal fun Instant.Companion.random() = Clock.System.now() + Random.nextInt(-1000, 1000).days

internal fun Instant.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

internal fun WorkflowName.Companion.random() = WorkflowName(String.random())

internal fun WorkflowVersion.Companion.random() = WorkflowVersion(String.random())

internal fun NodePosition.Companion.random() = NodePosition(listOf(String.random(), String.random(), String.random()))

internal fun NodeState.Companion.random() = NodeState(
    attemptIndex = Random.nextInt(),
    childIndex = Random.nextInt(),
    rawInput = JsonPrimitive(String.random()),
    rawOutput = JsonPrimitive(Random.nextInt()),
    context = JsonObject(mapOf(String.random() to JsonPrimitive(String.random()))),
    startedAt = Instant.random(),
    forIndex = Random.nextInt(),
)

internal fun InstanceMessage.Companion.random(): InstanceMessage {
    val randomPos = NodePosition.random()
    return InstanceMessage(
        workflowState = WorkflowState(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName.random(),
            workflowVersion = WorkflowVersion.random(),
            currentPosition = randomPos,
            currentStates = NodeStates(mapOf(randomPos to NodeState.random())),
        ),
        parentId = IDV7.random()
    )
}
