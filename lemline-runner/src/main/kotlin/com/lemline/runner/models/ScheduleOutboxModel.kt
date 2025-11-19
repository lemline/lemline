// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.utils.toDuration
import com.lemline.runner.messaging.InstanceMessage
import io.serverlessworkflow.api.types.Schedule
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

@ExperimentalSerializationApi
@ExperimentalTime
data class ScheduleOutboxModel(
    override val id: IDV7,
    override var instanceMessage: InstanceMessage<WorkflowCommand>,
    val initialScheduledFor: Instant?,
    val scheduleAfter: String?,
    val scheduleEvery: String?,
    val scheduleCron: String?,
    val scheduleZone: String?,
    override var outboxCompletedAt: Instant? = null

) : OutboxModel() {

    override var outboxScheduledFor: Instant? = initialScheduledFor

    override var outboxDelayedUntil: Instant? = initialScheduledFor

    override var outboxAttemptCount: Int = 0

    override var outboxFailedAt: Instant? = null

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    val after: Duration? by lazy { scheduleAfter?.let { Duration.parse(it) } }

    val every: Duration? by lazy { scheduleEvery?.let { Duration.parse(it) } }

    val cron: Cron? by lazy { scheduleCron?.let { cronParser.parse(it) } }

    val zone: ZoneId? by lazy { scheduleZone?.let { ZoneId.of(it) } }

    /**
     * Updates the scheduled execution instant from the schedule properties.
     *
     * This is called by [com.lemline.runner.outbox.ScheduleOutbox], before sending the related message
     */
    internal fun prepareNextScheduled(newId: WorkflowId) {
        // Reset the attempt counter and error tracking
        outboxAttemptCount = 0
        outboxFailedAt = null

        // Calculate the next scheduled execution time
        val currentTime = outboxScheduledFor ?: Clock.System.now()
        val nextScheduled = when {
            scheduleAfter != null -> null // One-time schedule after completion
            scheduleCron != null -> cron!!.getNextCronExecutionInstant(currentTime, zone)
            scheduleEvery != null -> currentTime + every!!
            else -> error("Invalid schedule model")
        }

        // Update both scheduled and delayed times
        outboxScheduledFor = nextScheduled
        outboxDelayedUntil = nextScheduled

        // Mark as completed if this is a cron schedule with no more executions
        if (nextScheduled == null && scheduleCron != null) {
            outboxCompletedAt = Clock.System.now()
        } else {
            outboxCompletedAt = null
        }

        // set a new id for the next workflow instance
        instanceMessage = instanceMessage.copy(
            workflowInfo = instanceMessage.workflowInfo.duplicate(newId),
        )
    }

    /**
     * Updates the scheduled execution instant from the after property.
     *
     * This is called by [com.lemline.runner.StepByStepRunner], after the current workflow instance has completed
     */
    fun scheduleAfterCompletion() {
        val nextTime = Clock.System.now() + after!!
        outboxScheduledFor = nextTime
        outboxDelayedUntil = nextTime
    }

    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }

        fun from(
            workflowId: WorkflowId,
            workflowNamespace: WorkflowNamespace,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            workflowInput: JsonElement,
            schedule: Schedule,
            zoneId: ZoneId?
        ): ScheduleOutboxModel {
            val scheduleEvery = schedule.every?.toDuration()?.toString()
            val scheduleAfter = schedule.after?.toDuration()?.toString()
            val scheduleCron = schedule.cron

            val now = Clock.System.now()

            val initialScheduledFor = when {
                scheduleAfter != null -> null
                scheduleCron != null -> cronParser.parse(scheduleCron)!!.getNextCronExecutionInstant(now, zoneId)
                scheduleEvery != null -> now + Duration.parse(scheduleEvery)
                else -> error("Invalid schedule model")
            }

            return ScheduleOutboxModel(
                id = IDV7.random(),
                instanceMessage = InstanceMessage.new(
                    workflowId = workflowId,
                    workflowNamespace = workflowNamespace,
                    workflowName = workflowName,
                    workflowVersion = workflowVersion,
                    workflowInput = workflowInput,
                    hasParent = false,
                ),
                scheduleEvery = scheduleEvery,
                scheduleAfter = scheduleAfter,
                scheduleCron = scheduleCron,
                scheduleZone = zoneId?.id,
                initialScheduledFor = initialScheduledFor
            )
        }
    }
}

@ExperimentalTime
internal fun Cron.getNextCronExecutionInstant(now: Instant, zoneId: ZoneId?): Instant? = ExecutionTime.forCron(this)
    .nextExecution(now.toJavaInstant().atZone(zoneId ?: ZoneId.of("UTC")))
    .map { it.toInstant().toKotlinInstant() }
    .orElse(null)
