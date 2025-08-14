// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.outbox.OutBoxStatus
import java.time.Instant

const val RETRY_TABLE = "lemline_retries"

data class RetryModel(
    override val workflowId: String,

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var scheduledFor: Instant? = Instant.now(),

    override var delayedUntil: Instant? = scheduledFor,

    override var attemptCount: Int = 0,

    override var lastError: String? = null,
) : OutboxModel()
