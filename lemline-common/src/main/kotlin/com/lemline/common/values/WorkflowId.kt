// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values
@JvmInline
value class WorkflowId(val value: IDV7) {
    override fun toString(): String = value.toString()
    companion object {
        fun random(): WorkflowId = WorkflowId(IDV7.random())
    }
}
