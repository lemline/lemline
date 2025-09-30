// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.common.values.WithWorkflowInfo
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.repositories.capabilities.IdColumn
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface InstanceBaseModel : IdColumn, WithOptionalWorkflowInfo, JsonSerializable {
    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7?
}

interface InstanceNullableModel : InstanceBaseModel {

    val workflowState: WorkflowState?

    override val workflowInfo: WorkflowInfo?

    override fun toJsonString(): String = LemlineJson.encodeToString(this)
}

interface InstanceModel : InstanceBaseModel, WithWorkflowInfo, JsonSerializable {

    val workflowState: WorkflowState

    override val workflowInfo: WorkflowInfo

    override fun toJsonString(): String = LemlineJson.encodeToString(this)
}
