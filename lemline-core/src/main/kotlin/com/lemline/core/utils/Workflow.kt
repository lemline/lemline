// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

val Workflow.name: WorkflowName get() = WorkflowName(document.name)

val Workflow.version: WorkflowVersion get() = WorkflowVersion(document.version)
