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
    @SerialName("i")
    override val id: @Contextual UUID,

    @SerialName("w")
    override var instance: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,

    @SerialName("sa")
    val scheduleAfter: String?,

    @SerialName("se")
    val scheduleEvery: String?,

    @SerialName("sc")
    val scheduleCron: String?,

    @SerialName("sz")
    val scheduleZone: String?,
) : IngestionMessage {
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
