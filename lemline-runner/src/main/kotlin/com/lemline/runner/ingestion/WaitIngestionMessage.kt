// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("w") // <- type discriminator for polymorphic serialization
data class WaitIngestionMessage(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("s")
    override val outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override val outboxScheduledFor: Instant?,
) : OutboxIngestionMessage, IngestionMessage {
    fun toModel() = WaitOutboxModel(
        id = id,
        instanceMessage = instanceMessage,
        outBoxStatus = outBoxStatus,
        outboxScheduledFor = outboxScheduledFor
    )
}
