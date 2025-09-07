// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WorkflowMessage : JsonSerializable {

    val workflowId: WorkflowId?

    val workflowName: WorkflowName?

    val workflowVersion: WorkflowVersion?

}
