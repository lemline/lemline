// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.parents

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.*
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

fun ParentModel.Companion.random() = ParentModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomRunWorkflowStartedEvent(),
    ),
    childId = WorkflowId.random()
)
