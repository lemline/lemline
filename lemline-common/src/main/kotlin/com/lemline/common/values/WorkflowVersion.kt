// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.github.zafarkhaja.semver.Version
import io.serverlessworkflow.api.types.Workflow

@JvmInline
value class WorkflowVersion(private val value: String) {
    val isLatest: Boolean get() = value == "latest"

    fun comparePrecedenceTo(other: WorkflowVersion): Int =
        runCatching { Version.parse(value).compareTo(Version.parse(other.value)) }
            .getOrElse { value.compareTo(other.value) }

    override fun toString(): String = value
}

val Workflow.version get() = WorkflowVersion(document.version)
