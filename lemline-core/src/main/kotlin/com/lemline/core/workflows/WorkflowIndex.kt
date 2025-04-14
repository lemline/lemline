package com.lemline.core.workflows

import io.serverlessworkflow.api.types.Workflow

typealias WorkflowIndex = Pair<String, String>

val Workflow.index: WorkflowIndex
    get() = document.name to document.version