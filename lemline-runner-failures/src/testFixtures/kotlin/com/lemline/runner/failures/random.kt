// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.failures

import com.lemline.common.random.nullableRandom
import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.*
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
