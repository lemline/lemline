// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.forks

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.random
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
