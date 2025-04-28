// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.github.f4b6a3.uuid.UuidCreator
import com.lemline.runner.outbox.OutBoxStatus
import java.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val WAIT_TABLE = "waits"

@Serializable
data class WaitModel(
    override val id: String = UuidCreator.getTimeOrderedEpoch().toString(),

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var delayedUntil: @Contextual Instant,

    override var attemptCount: Int = 0,

    override var lastError: String? = null

) : OutboxModel() {

    companion object {
        fun create(
            message: String,
            delayedUntil: Instant = Instant.now(),
            attemptCount: Int = 0,
            lastError: Exception? = null,
            status: OutBoxStatus = OutBoxStatus.PENDING,
        ) = WaitModel(
            message = message,
            delayedUntil = delayedUntil,
            attemptCount = attemptCount,
            lastError = lastError?.message,
            status = status
        )
    }
}
