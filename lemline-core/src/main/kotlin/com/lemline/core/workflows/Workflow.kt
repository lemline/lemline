// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

val Workflow.namespace: WorkflowNamespace get() = WorkflowNamespace(document.namespace)

val Workflow.name: WorkflowName get() = WorkflowName(document.name)

val Workflow.version: WorkflowVersion get() = WorkflowVersion(document.version)

/**
 * Uniquely identifying a workflow by its namespace, name and version.
 */
data class WorkflowIndex(
    val namespace: WorkflowNamespace,
    val name: WorkflowName,
    val version: WorkflowVersion
)

val Workflow.index
    get() = WorkflowIndex(
        WorkflowNamespace(document.namespace),
        WorkflowName(document.name),
        WorkflowVersion(document.version)
    )
