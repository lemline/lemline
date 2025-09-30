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
import com.lemline.core.utils.toDuration
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.bases.OptionalOutboxModel
import com.lemline.runner.outbox.bases.RunStatus
import io.serverlessworkflow.api.types.Schedule
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("s") // <- type discriminator for polymorphic serialization
data class ScheduleModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override var instanceMessage: InstanceMessage,

    @SerialName("rs")
    override var runStatus: RunStatus = RunStatus.PENDING,

    @SerialName("ra")
    override var runAt: Instant?,

    @SerialName("sa")
    val scheduleAfter: String?,

    @SerialName("se")
    val scheduleEvery: String?,

    @SerialName("sc")
    val scheduleCron: String?,

    @SerialName("sz")
    val scheduleZone: String?

) : IngestionModel, OptionalOutboxModel {

    @Transient
    override var runDelayedUntil: Instant? = runAt

    @Transient
    override var runAttemptCount: Int = 0

    @Transient
    override var runLastErrorClass: String? = null

    @Transient
    override var runLastErrorMessage: String? = null

    @Transient
    override var runLastErrorStackTrace: String? = null

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
        // Reset the attempt counter
        runAttemptCount = 0
        // Calculate the next scheduled execution time
        runDelayedUntil = when {
            scheduleAfter != null -> null
            scheduleCron != null -> runAt?.let { cron!!.getNextCronExecutionInstant(it, zone) }
            scheduleEvery != null -> runAt?.let { it + every!! }
            else -> error("Invalid schedule model")
        }?.also { runAt = it }
        // update the status
        runStatus = when {
            runDelayedUntil == null && scheduleCron != null -> RunStatus.DONE // <- only case when the schedule is completed
            else -> RunStatus.PENDING
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
        runDelayedUntil = Clock.System.now() + after!!
        runAt = runDelayedUntil
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
        ): ScheduleModel {
            val scheduleEvery = schedule.every?.toDuration()?.toString()
            val scheduleAfter = schedule.after?.toDuration()?.toString()
            val scheduleCron = schedule.cron

            val now = Clock.System.now()

            val scheduledFor = when {
                scheduleAfter != null -> null
                scheduleCron != null -> cronParser.parse(scheduleCron)!!.getNextCronExecutionInstant(now, zoneId)
                scheduleEvery != null -> now + Duration.parse(scheduleEvery)
                else -> error("Invalid schedule model")
            }

            return ScheduleModel(
                id = IDV7.random(),
                instanceMessage = InstanceMessage.new(
                    workflowId = workflowId,
                    workflowNamespace = workflowNamespace,
                    workflowName = workflowName,
                    workflowVersion = workflowVersion,
                    workflowInput = workflowInput,
                    parentId = null,
                ),
                scheduleEvery = scheduleEvery,
                scheduleAfter = scheduleAfter,
                scheduleCron = scheduleCron,
                scheduleZone = zoneId?.id,

                runAt = scheduledFor,
                runStatus = RunStatus.PENDING,
            )
        }
    }
}

@ExperimentalTime
internal fun Cron.getNextCronExecutionInstant(now: Instant, zoneId: ZoneId?): Instant? = ExecutionTime.forCron(this)
    .nextExecution(now.toJavaInstant().atZone(zoneId ?: ZoneId.of("UTC")))
    .map { it.toInstant().toKotlinInstant() }
    .orElse(null)
