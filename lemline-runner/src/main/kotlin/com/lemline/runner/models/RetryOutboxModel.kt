// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ExperimentalTime
data class RetryOutboxModel(
    override val id: IDV7,

    override val instanceMessage: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxErrorClass: String? = null,

    override var outboxErrorMessage: String? = null,

    override var outboxErrorStackTrace: String? = null,

    val errorClass: String,

    val errorMessage: String?,

    val errorStackTrace: String,
) : OutboxModel
