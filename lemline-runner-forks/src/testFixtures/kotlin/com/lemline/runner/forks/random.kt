// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.core.random.random
import com.lemline.core.random.randomForkStartedEvent
import com.lemline.core.random.randomWorkflowInfo
import com.lemline.runner.common.messaging.InstanceMessage
import kotlin.random.Random

fun randomForkModel() = ForkModel(
    instanceMessage = InstanceMessage(
        workflowInfo = randomWorkflowInfo(),
        workflowState = randomForkStartedEvent(),
    ),
    compete = Random.nextBoolean(),
    id = IDV7.random(),
    position = NodePosition.random().toString()
)

fun randomForkBranchModel(forkId: IDV7 = IDV7.random()) = ForkBranchModel(
    forkId = forkId,
    branchPosition = String.random()
)
