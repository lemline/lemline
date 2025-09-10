// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import io.serverlessworkflow.api.types.Workflow

/**
 * Pair representing the index of a workflow consisting of its name and version.
 */
typealias WorkflowIndex = Pair<WorkflowName, WorkflowVersion>

val Workflow.index: WorkflowIndex
    get() = WorkflowName(document.name) to WorkflowVersion(document.version)
