// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

interface WorkflowIdentification {
    val id: String?

    val name: String?

    val version: String?

    val position: String?

    fun toJsonString(): String
}
