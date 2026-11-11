// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.utils.toDuration
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import io.serverlessworkflow.api.types.Schedule
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.json.JsonElement

data class ScheduleModel(
    /** Unique identifier for this scheduled workflow execution */
    override val id: IDV7,

    override var instanceMessage: InstanceMessage<WorkflowCommand.ResumeFromTask>,

    override val outboxScheduledFor: Instant?,

    /** ISO 8601 duration for completion-based recurring execution (e.g., PT1H for 1 hour) */
    val scheduleAfter: String?,

    /** ISO 8601 duration for recurring interval execution (e.g., PT5M for every 5 minutes) */
    val scheduleEvery: String?,

    /** Unix cron expression for cron-based scheduling (e.g., "0 9 * * *" for daily at 9 AM) */
    val scheduleCron: String?,

    /** IANA timezone identifier for cron schedules (e.g., "America/New_York") */
    val scheduleZone: String?,

    /** Stable identity shared by all occurrences of this schedule. */
    val scheduleId: IDV7 = id,
) : WithInstanceMessage, WithId, WithOutbox, WithCleanup {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    override var cleanupAfter: Instant? = null

    /** Parsed duration for completion-based recurring execution, null if not applicable */
    val after: Duration? by lazy { scheduleAfter?.let { Duration.parse(it) } }

    /** Parsed duration for recurring interval execution, null if not applicable */
    val every: Duration? by lazy { scheduleEvery?.let { Duration.parse(it) } }

    /** Parsed cron expression for cron-based scheduling, null if not applicable */
    val cron: Cron? by lazy { scheduleCron?.let { cronParser.parse(it) } }

    /** Parsed timezone for cron-based scheduling, null if not specified */
    val zone: ZoneId? by lazy { scheduleZone?.let { ZoneId.of(it) } }

    /** One successor per occurrence, including when completion events are redelivered. */
    val nextOccurrenceId: IDV7 get() = id.derive("-next")

    /** Keep the existing ID derivation so retries of previously persisted occurrences remain stable. */
    val executionId: WorkflowId get() = WorkflowId(id.derive("-wf-${outboxScheduledFor?.atDatabasePrecision()}"))
    val messageId: IDV7 get() = id.derive("-msg-${outboxScheduledFor?.atDatabasePrecision()}")

    /** Prepare this occurrence without advancing its deadline or resetting its retry state. */
    fun prepareExecution() {
        instanceMessage = instanceMessage.copy(
            workflowState = instanceMessage.workflowState.duplicate(
                workflowId = executionId,
                startedAt = if (outboxAttemptCount <= 1) Clock.System.now()
                else instanceMessage.workflowState.nodeStack.rootState.startedAt,
            )
        )
    }

    /** Fixed-rate schedules advance from the occurrence deadline, including when catching up. */
    fun nextScheduled(): ScheduleModel? {
        if (scheduleAfter != null) return null
        val currentTime = requireNotNull(outboxScheduledFor) { "Missing occurrence deadline" }
        val nextTime = when {
            scheduleCron != null -> cron!!.getNextCronExecutionInstant(currentTime, zone)
            scheduleEvery != null -> currentTime + every!!.also { require(it > Duration.ZERO) }
            else -> error("Invalid schedule model: $this")
        }
        return nextTime?.let(::nextOccurrence)
    }

    fun nextAfterCompletion(completedAt: Instant): ScheduleModel =
        nextOccurrence(completedAt + requireNotNull(after))

    private fun nextOccurrence(scheduledFor: Instant): ScheduleModel {
        val nextId = nextOccurrenceId
        // copy() creates fresh outbox/retry/cleanup fields while retaining the schedule definition.
        return copy(id = nextId, outboxScheduledFor = scheduledFor.atDatabasePrecision()).let { next ->
            next.copy(
                instanceMessage = instanceMessage.copy(
                    workflowState = instanceMessage.workflowState.duplicate(
                        workflowId = next.executionId,
                        startedAt = scheduledFor.atDatabasePrecision(),
                    )
                )
            )
        }
    }

    companion object Companion {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }

        fun from(
            workflowId: WorkflowId,
            workflowNamespace: WorkflowNamespace,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            workflowInput: JsonElement,
            schedule: Schedule,
            zoneId: ZoneId?
        ): ScheduleModel {
            val scheduleEvery = schedule.every?.toDuration()?.toString()
            val scheduleAfter = schedule.after?.toDuration()?.toString()
            val scheduleCron = schedule.cron

            val now = Clock.System.now().atDatabasePrecision()

            val initialScheduledFor = when {
                scheduleAfter != null -> null
                scheduleCron != null -> cronParser.parse(scheduleCron)!!.getNextCronExecutionInstant(now, zoneId)
                scheduleEvery != null -> now + Duration.parse(scheduleEvery)
                else -> error("Invalid schedule model")
            }?.atDatabasePrecision()

            return ScheduleModel(
                id = IDV7.random(),
                instanceMessage = InstanceMessage(
                    workflowInfo = WorkflowInfo(workflowNamespace, workflowName, workflowVersion),
                    workflowState = StepByStepOrchestrator.initCmd(workflowId, workflowInput, false)
                ),
                scheduleEvery = scheduleEvery,
                scheduleAfter = scheduleAfter,
                scheduleCron = scheduleCron,
                scheduleZone = zoneId?.id,
                outboxScheduledFor = initialScheduledFor
            ).also { model ->
                if (initialScheduledFor != null) {
                    model.instanceMessage = model.instanceMessage.copy(
                        workflowState = model.instanceMessage.workflowState.duplicate(
                            workflowId = model.executionId,
                            startedAt = initialScheduledFor,
                        )
                    )
                }
            }
        }
    }
}

internal fun Cron.getNextCronExecutionInstant(now: Instant, zoneId: ZoneId?): Instant? = ExecutionTime.forCron(this)
    .nextExecution(now.toJavaInstant().atZone(zoneId ?: ZoneId.of("UTC")))
    .map { it.toInstant().toKotlinInstant() }
    .orElse(null)

/** SQL timestamp columns store microseconds; identity must survive a database round trip. */
private fun Instant.atDatabasePrecision(): Instant =
    Instant.fromEpochSeconds(epochSeconds, (nanosecondsOfSecond / 1_000 * 1_000).toLong())
