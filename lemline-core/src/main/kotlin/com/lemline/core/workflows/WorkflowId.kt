// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.ids.IdGenerator
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import java.util.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class WorkflowId(val value: @Contextual UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): WorkflowId = WorkflowId(IdGenerator.generateV7())

        fun from(descriptor: WorkflowDescriptor): WorkflowId = WorkflowId(UUID.fromString(descriptor.id))
    }
}
