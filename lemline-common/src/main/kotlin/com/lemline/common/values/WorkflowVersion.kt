// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class WorkflowVersion(private val value: String) {
    override fun toString(): String = value
}
