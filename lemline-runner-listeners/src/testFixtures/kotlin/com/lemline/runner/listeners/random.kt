// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.random
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

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
