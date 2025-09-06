// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.json.LemlineJson
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.messaging.WorkflowMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface IngestionMessage : WorkflowMessage {
    val instanceMessage: InstanceMessage?

    val workflowState: WorkflowState? get() = instanceMessage?.workflowState

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(str: String): IngestionMessage = LemlineJson.decodeFromString(str)
    }

    override val workflowId get() = instanceMessage?.workflowId

    override val workflowName get() = instanceMessage?.workflowName

    override val workflowVersion get() = instanceMessage?.workflowVersion
}
