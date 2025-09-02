// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("r") // <- type discriminator for polymorphic serialization
data class RetryIngestionMessage(
    @SerialName("i")
    override val id: @Contextual UUID,

    @SerialName("w")
    override val instance: InstanceMessage?,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,

    @SerialName("m")
    val message: String? = null,
) : IngestionMessage {
    fun toModel() = RetryOutboxModel(
        id = id,
        instance = instance,
        outBoxStatus = outBoxStatus,
        outboxScheduledFor = outboxScheduledFor,
        message = message
    )
}
