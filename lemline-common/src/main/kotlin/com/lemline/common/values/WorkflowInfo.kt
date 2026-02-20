// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values
import io.serverlessworkflow.api.types.Workflow

data class WorkflowInfo(
    /**
     * The namespace of the workflow.
     */
    val namespace: WorkflowNamespace,
    /**
     * The name of the workflow.
     */
    val name: WorkflowName,
    /**
     * The version of the workflow.
     */
    val version: WorkflowVersion
) {
    val qualifiedName: String by lazy { "$namespace/$name:$version" }
}

val Workflow.info get() = WorkflowInfo(this.namespace, this.name, this.version)
