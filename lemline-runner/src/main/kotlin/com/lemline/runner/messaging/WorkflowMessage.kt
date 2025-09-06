package com.lemline.runner.messaging

import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WorkflowMessage : JsonSerializable {

    val workflowId: WorkflowId?

    val workflowName: WorkflowName?

    val workflowVersion: WorkflowVersion?

}
