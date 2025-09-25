// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.random

import com.lemline.common.random.random
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowState
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

fun JsonElement.Companion.random(): JsonElement {
    return when (Random.nextInt(6)) {
        0 -> JsonNull
        1 -> JsonPrimitive(Random.nextBoolean())
        2 -> JsonPrimitive(Random.nextInt())
        3 -> JsonPrimitive(Random.nextDouble())
        4 -> JsonPrimitive(String.random())
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

fun NodeStates.Companion.random() = NodeStates(
    mapOf(
        NodePosition.random() to NodeState.random(),
        NodePosition.random() to NodeState.random(),
        NodePosition.random() to NodeState.random(),
    )
)

fun WorkflowState.Companion.random(): WorkflowState = WorkflowState(
    workflowId = WorkflowId.random(),
    workflowNamespace = WorkflowNamespace.random(),
    workflowName = WorkflowName.random(),
    workflowVersion = WorkflowVersion.random(),
    currentPosition = NodePosition.random(),
    currentStates = NodeStates.random(),
)
