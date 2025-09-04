// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.json.LemlineJson
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.messaging.LemlineMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface IngestionMessage : LemlineMessage {
    val instanceMessage: InstanceMessage?

    override val workflowState: WorkflowState? get() = instanceMessage?.workflowState

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(str: String): IngestionMessage = LemlineJson.decodeFromString(str)
    }
}
