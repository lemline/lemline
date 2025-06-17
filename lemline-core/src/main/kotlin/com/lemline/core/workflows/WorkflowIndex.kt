// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import io.serverlessworkflow.api.types.Workflow

/**
 * Pair representing the index of a workflow consisting of its name and version.
 */
typealias WorkflowIndex = Pair<String, String>

val Workflow.index: WorkflowIndex
    get() = document.name to document.version
