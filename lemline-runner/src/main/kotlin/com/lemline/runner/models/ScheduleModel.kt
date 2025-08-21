// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.lemline.common.ids.IdGenerator
import com.lemline.core.nodes.NodePosition
import com.lemline.core.utils.toDuration
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.LemlineMessage
import com.lemline.runner.outbox.OutBoxStatus
import io.serverlessworkflow.api.types.Schedule
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.json.JsonElement

const val SCHEDULE_TABLE = "lemline_schedules"

@ExperimentalTime
data class ScheduleModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    override var workflowId: String,

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

    val scheduleEvery: String?,

    val scheduleCron: String?,

    val scheduleZone: String?,

    ) : OutboxModel() {

    val after: Duration? by lazy { scheduleAfter?.let { Duration.parse(it) } }

    val every: Duration? by lazy { scheduleEvery?.let { Duration.parse(it) } }

    val cron: Cron? by lazy { scheduleCron?.let { cronParser.parse(it) } }

    val zone: ZoneId? by lazy { scheduleZone?.let { ZoneId.of(it) } }

    override fun toLemlineMessage() = LemlineMessage.fromStrings(
        workflowId = workflowId,
        workflowName = workflowName,
        workflowVersion = workflowVersion,
        workflowPosition = workflowPosition,
        workflowState = workflowState,
        isScheduledAfter = scheduleAfter != null,
    )

    /**
     * Updates the scheduled execution instant from the schedule properties.
     *
     * This is called by [com.lemline.runner.outbox.ScheduleOutbox], before sending the related message
     */
    fun updateScheduledExecutionInstant() {
        // Calculate the next scheduled execution time
        outboxDelayedUntil = when {
            scheduleAfter != null -> null
            scheduleCron != null -> outboxScheduledFor?.let { cron!!.getNextCronExecutionInstant(it, zone) }
            scheduleEvery != null -> outboxScheduledFor?.let { it + every!! }
            else -> error("Invalid schedule model")
        }?.also { outboxScheduledFor = it }
        // update the status
        outBoxStatus = when {
            outboxDelayedUntil == null && scheduleCron != null -> OutBoxStatus.SENT // <- only case when the schedule is completed
            else -> OutBoxStatus.PENDING
        }
        // set a new id for the next workflow instance
        // TODO Manage idempotency by providing a deterministic workflow id
        workflowId = IdGenerator.generateTimeBasedId()
    }

    /**
     * Updates the scheduled execution instant from the after property.
     *
     * This is called by [com.lemline.runner.StepByStepRunner], after the current workflow instance has completed
     */
    fun updateScheduledForAfterCompletion() {
        outboxDelayedUntil = Clock.System.now() + after!!
        outboxScheduledFor = outboxDelayedUntil
    }

    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }

        fun create(
            workflowId: String,
            workflowName: String,
            workflowVersion: String,
            workflowInput: JsonElement,
            schedule: Schedule,
            zoneId: ZoneId?
        ): ScheduleModel {
            val scheduleEvery = schedule.every?.toDuration()?.toString()
            val scheduleAfter = schedule.after?.toDuration()?.toString()
            val scheduleCron = schedule.cron

            val now = Clock.System.now()

            val scheduledFor = when {
                scheduleAfter != null -> now
                scheduleCron != null -> cronParser.parse(scheduleCron)!!.getNextCronExecutionInstant(now, zoneId)
                scheduleEvery != null -> now
                else -> error("Invalid schedule model")
            }

            val scheduleModel = ScheduleModel(
                id = workflowId,
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                scheduleEvery = scheduleEvery,
                scheduleAfter = scheduleAfter,
                scheduleCron = scheduleCron,
                scheduleZone = zoneId?.id,
                workflowPosition = NodePosition.root.toString(),
                workflowState = WorkflowState.newInstance(workflowInput).toJsonString(),
                outboxScheduledFor = scheduledFor
            )

            return scheduleModel
        }
    }
}

@ExperimentalTime
private fun Cron.getNextCronExecutionInstant(now: Instant, zoneId: ZoneId?): Instant? = ExecutionTime.forCron(this)
    .nextExecution(now.toJavaInstant().atZone(zoneId ?: ZoneId.of("UTC")))
    .map { it.toInstant().toKotlinInstant() }
    .orElse(null)
