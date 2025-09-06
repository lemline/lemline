// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.lemline.core.utils.toDuration
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.models.getNextCronExecutionInstant
import com.lemline.runner.outbox.OutBoxStatus
import io.serverlessworkflow.api.types.Schedule
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("s") // <- type discriminator for polymorphic serialization
data class ScheduleIngestionMessage(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override var instanceMessage: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,

    @SerialName("sa")
    val scheduleAfter: String? = null,

    @SerialName("se")
    val scheduleEvery: String? = null,

    @SerialName("sc")
    val scheduleCron: String? = null,

    @SerialName("sz")
    val scheduleZone: String? = null,
) : OutboxIngestionMessage, IngestionMessage {
    fun toModel() = ScheduleOutboxModel(
        id = id,
        instanceMessage = instanceMessage,
        outBoxStatus = outBoxStatus,
        outboxScheduledFor = outboxScheduledFor,
        scheduleAfter = scheduleAfter,
        scheduleEvery = scheduleEvery,
        scheduleCron = scheduleCron,
        scheduleZone = scheduleZone
    )

    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }

        fun from(
            workflowId: WorkflowId,
            workflowName: WorkflowName,
            workflowVersion: WorkflowVersion,
            workflowInput: JsonElement,
            schedule: Schedule,
            zoneId: ZoneId?
        ): ScheduleIngestionMessage {
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

            val scheduleIngestionMessage = ScheduleIngestionMessage(
                id = IDV7.new(),
                instanceMessage = InstanceMessage.new(
                    workflowId = workflowId,
                    workflowName = workflowName,
                    workflowVersion = workflowVersion,
                    workflowInput = workflowInput,
                    parentId = null,
                ),
                scheduleEvery = scheduleEvery,
                scheduleAfter = scheduleAfter,
                scheduleCron = scheduleCron,
                scheduleZone = zoneId?.id,

                outboxScheduledFor = scheduledFor
            )

            return scheduleIngestionMessage
        }
    }
}
