// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface InstanceModel : WithId, WithOptionalWorkflowInfo, JsonSerializable {

    val workflowState: WorkflowState?

    override fun toJsonString(): String = LemlineJson.encodeToString(this)
}
