// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values
import io.serverlessworkflow.api.types.Workflow
@JvmInline
value class WorkflowVersion(private val value: String) {
    override fun toString(): String = value
}
val Workflow.version get() = WorkflowVersion(document.version)
