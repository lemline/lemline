// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.lemline.common.ids.IdGenerator
import com.lemline.runner.outbox.OutBoxStatus
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

const val SCHEDULE_TABLE = "lemline_schedules"

@ExperimentalTime
data class ScheduleModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    override val workflowId: String,

    override val workflowVersion: String,

    override val workflowName: String,

    override val workflowPosition: String,

    override val workflowState: String,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxLastError: String? = null,

    val scheduleAfter: String?,

    val scheduleCron: String?,

    val scheduleEvery: String?,

    ) : OutboxModel() {

    val cron: Cron? by lazy { scheduleCron?.let { cronParser.parse(it) } }

    val after: Duration? by lazy { scheduleAfter?.let { Duration.parse(it) } }

    val every: Duration? by lazy { scheduleEvery?.let { Duration.parse(it) } }

    // get the next instant according to the provided cron expression for the current time zone of the system
    fun getNextScheduledExecutionInstant(zoneId: ZoneId = ZoneId.systemDefault()): Instant? = outboxScheduledFor?.let {
        ExecutionTime.forCron(cron)
            .nextExecution(it.toJavaInstant().atZone(zoneId))
            .map { it.toInstant().toKotlinInstant() }
            .orElse(null)
    }

    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }
    }
}
