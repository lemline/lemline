// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val RETRY_TABLE = "lemline_retries"

@ExperimentalTime
data class RetryModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    override val workflowId: String,

    override val workflowVersion: String,

    override val workflowName: String,

    override val workflowPosition: String,

    override val workflowState: String,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant? = Clock.System.now(),

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxLastError: String? = null,

    val message: String?,
) : OutboxModel()
