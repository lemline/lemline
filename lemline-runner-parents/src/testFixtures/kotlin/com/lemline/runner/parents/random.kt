// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.random.randomRunWorkflowStartedEvent
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage

fun ParentModel.Companion.random() = ParentModel(
    id = IDV7.random(),
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomRunWorkflowStartedEvent(),
    ),
    childId = WorkflowId.random()
)
