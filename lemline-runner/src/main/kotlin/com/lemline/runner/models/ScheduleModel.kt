// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.utils.IdGenerator
import com.lemline.runner.outbox.OutBoxStatus
import java.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val SCHEDULE_TABLE = "lemline_schedules"

@Serializable
data class ScheduleModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    val cron: String?,

    val after: String?,

    val every: String?,

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var delayedUntil: @Contextual Instant?,

    override var attemptCount: Int = 0,

    override var lastError: String? = null

) : OutboxModel()
