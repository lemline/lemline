// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.ScheduleOutboxModel
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
@SerialName("s") // <- type discriminator for polymorphic serialization
data class ScheduleIngestionMessage(
    @SerialName("id")
    override val id: @Contextual UUID,

    @SerialName("i")
    override var instance: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,

    @SerialName("sa")
    val scheduleAfter: String? = null,

    @SerialName("se")
    val scheduleEvery: String? = null,

    @SerialName("sc")
    val scheduleCron: String? = null,

    @SerialName("sz")
    val scheduleZone: String? = null,
) : OutboxIngestionMessage, IngestionMessage {
    fun toModel() = ScheduleOutboxModel(
        id = id,
        instance = instance,
        outBoxStatus = outBoxStatus,
        outboxScheduledFor = outboxScheduledFor,
        scheduleAfter = scheduleAfter,
        scheduleEvery = scheduleEvery,
        scheduleCron = scheduleCron,
        scheduleZone = scheduleZone
    )
}
