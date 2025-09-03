// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.lemline.core.workflows.WorkflowId
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

@ExperimentalTime
data class ScheduleOutboxModel(
    override val id: IDV7,

    override var instanceMessage: InstanceMessage,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant?,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxErrorClass: String? = null,

    override var outboxErrorMessage: String? = null,

    override var outboxErrorStackTrace: String? = null,

    val scheduleAfter: String?,

    val scheduleEvery: String?,

    val scheduleCron: String?,

    val scheduleZone: String?,

    ) : OutboxModel {

    val after: Duration? by lazy { scheduleAfter?.let { Duration.parse(it) } }

    val every: Duration? by lazy { scheduleEvery?.let { Duration.parse(it) } }

    val cron: Cron? by lazy { scheduleCron?.let { cronParser.parse(it) } }

    val zone: ZoneId? by lazy { scheduleZone?.let { ZoneId.of(it) } }


    /**
     * Updates the scheduled execution instant from the schedule properties.
     *
     * This is called by [com.lemline.runner.outbox.ScheduleOutbox], before sending the related message
     */
    internal fun updateBeforeProcessing() {
        // Reset the attempt counter
        outboxAttemptCount = 0
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
        instanceMessage = instanceMessage.copy(
            workflowInstance = instanceMessage.workflowInstance.copy(workflowId = WorkflowId.new()),
            // <- TODO Manage idempotency by providing a deterministic workflow id
        )
    }

    /**
     * Updates the scheduled execution instant from the after property.
     *
     * This is called by [com.lemline.runner.StepByStepRunner], after the current workflow instance has completed
     */
    fun scheduleAfterCompletion() {
        outboxDelayedUntil = Clock.System.now() + after!!
        outboxScheduledFor = outboxDelayedUntil
    }

    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }
    }
}

@ExperimentalTime
internal fun Cron.getNextCronExecutionInstant(now: Instant, zoneId: ZoneId?): Instant? = ExecutionTime.forCron(this)
    .nextExecution(now.toJavaInstant().atZone(zoneId ?: ZoneId.of("UTC")))
    .map { it.toInstant().toKotlinInstant() }
    .orElse(null)
