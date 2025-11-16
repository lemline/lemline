// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("w") // <- type discriminator for polymorphic serialization
data class WaitOutboxModel(
    @SerialName("id")
    override val id: IDV7 = IDV7.random(),

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,
) : OutboxModel() {

    init {
        require(outboxScheduledFor != null) { "outboxScheduledFor cannot be null" }
    }

    @Transient
    override var outboxDelayedUntil: Instant? = outboxScheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null" }
            field = until
        }

    @Transient
    override var outboxAttemptCount: Int = 0

    @Transient
    override var outboxErrorClass: String? = null

    @Transient
    override var outboxErrorMessage: String? = null

    @Transient
    override var outboxErrorStackTrace: String? = null

    // Needed by tests
    companion object
}
