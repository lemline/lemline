// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.github.f4b6a3.uuid.UuidCreator
import com.lemline.runner.outbox.OutBoxStatus
import java.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val RETRY_TABLE = "retries"

@Serializable
data class RetryModel(
    override val id: String = UuidCreator.getTimeOrderedEpoch().toString(),

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var delayedUntil: @Contextual Instant = Instant.now(),

    override var attemptCount: Int = 0,

    override var lastError: String? = null,
) : OutboxModel()
