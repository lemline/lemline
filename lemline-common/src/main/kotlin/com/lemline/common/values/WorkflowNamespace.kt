// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values
import io.serverlessworkflow.api.types.Workflow
@JvmInline
value class WorkflowNamespace(private val value: String) {
    override fun toString(): String = value
}
val Workflow.namespace get() = WorkflowNamespace(document.namespace)
