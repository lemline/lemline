// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.workflows.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface InstanceModel : WithId, WithInstanceInfo {

    val workflowState: WorkflowState?

    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7?


    override val workflowId get() = workflowState?.workflowId

    override val workflowName get() = workflowState?.workflowName

    override val workflowVersion get() = workflowState?.workflowVersion
}
