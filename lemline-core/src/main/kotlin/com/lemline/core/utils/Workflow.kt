// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

val Workflow.namespace: WorkflowNamespace get() = WorkflowNamespace(document.namespace)

val Workflow.name: WorkflowName get() = WorkflowName(document.name)

val Workflow.version: WorkflowVersion get() = WorkflowVersion(document.version)
