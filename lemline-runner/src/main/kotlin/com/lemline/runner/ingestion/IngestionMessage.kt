// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.flexible.LazyParsedField
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.WithId
import com.lemline.runner.outbox.OutBoxStatus
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface IngestionMessage : WithId {
    @SerialName("w")
    val instance: InstanceMessage?

    @SerialName("s")
    var outBoxStatus: OutBoxStatus

    @SerialName("f")
    var outboxScheduledFor: Instant?

    fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(str: String): IngestionMessage = LemlineJson.decodeFromString(str)
    }

    val workflowId: UUID? get() = instance?.workflowId

    val workflowName: String? get() = instance?.workflowName

    val workflowVersion: String? get() = instance?.workflowVersion

    val workflowPosition: LazyParsedField<NodePosition>? get() = instance?.workflowPosition
}
