// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.JsonSerializable
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface InstanceModel : WithId, WithInstanceInfo, JsonSerializable {

    val workflowState: WorkflowState?

    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7?

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    override val workflowId get() = workflowState?.workflowId

    override val workflowNamespace get() = workflowState?.workflowNamespace

    override val workflowName get() = workflowState?.workflowName

    override val workflowVersion get() = workflowState?.workflowVersion
}
