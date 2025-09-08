package com.lemline.runner.models

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion

interface WithInstanceInfo {

    val workflowId: WorkflowId?

    val workflowName: WorkflowName?

    val workflowVersion: WorkflowVersion?
}
