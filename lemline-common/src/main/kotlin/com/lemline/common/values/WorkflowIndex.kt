// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import io.serverlessworkflow.api.types.Workflow

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
