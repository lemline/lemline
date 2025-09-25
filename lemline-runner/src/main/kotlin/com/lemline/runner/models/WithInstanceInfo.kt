// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion

interface WithInstanceInfo {

    val workflowId: WorkflowId?

    val workflowNamespace: WorkflowNamespace?

    val workflowName: WorkflowName?

    val workflowVersion: WorkflowVersion?
}
