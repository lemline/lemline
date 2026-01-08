// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import io.serverlessworkflow.api.types.Workflow
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class WorkflowName(private val value: String) {
    override fun toString(): String = value
}

val Workflow.name get() = WorkflowName(document.name)
