// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.cronutils.model.Cron
import com.cronutils.model.time.ExecutionTime
import com.lemline.runner.outbox.OutBoxStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

const val SCHEDULE_TABLE = "lemline_schedules"

data class ScheduleModel(
    override val workflowId: String,

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var scheduledFor: Instant?,

    override var delayedUntil: Instant? = scheduledFor,

    override var attemptCount: Int = 0,

    override var lastError: String? = null,

    val cron: Cron?,

    val after: Duration?,

    val every: Duration?,

    ) : OutboxModel() {

    // get the next instant according to the provided cron expression for the current time zone of the system
    fun getNextScheduledExecutionInstant(zoneId: ZoneId = ZoneId.systemDefault()): Instant? = scheduledFor?.let {
        ExecutionTime.forCron(cron)
            .nextExecution(it.atZone(zoneId))
            .map { it.toInstant() }
            .orElse(null)
    }
}
