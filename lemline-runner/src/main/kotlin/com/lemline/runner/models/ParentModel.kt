// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val PARENT_TABLE = "lemline_parent_workflows"

@ExperimentalTime
data class ParentModel(
    override val id: UUID = IdGenerator.generateUUIDV7(),

    override val instance: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxLastError: String? = null,
) : OutboxModel()
