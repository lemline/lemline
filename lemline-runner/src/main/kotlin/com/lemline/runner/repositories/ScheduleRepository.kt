// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.workflows.WorkflowId
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.ScheduleOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val SCHEDULE_TABLE = "lemline_schedules"

/**
 * Repository for managing wait messages in the outbox pattern.
 * This repository handles the persistence and retrieval of wait messages,
 * which are used to implement delayed execution in workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see WaitOutboxModel for the message model
 * @see com.lemline.runner.outbox.OutboxRelay for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
class ScheduleRepository : OutboxRepository<ScheduleOutboxModel>() {

    companion object {
        internal const val SCHEDULE_AFTER_COLUMN = "schedule_after"
        internal const val SCHEDULE_CRON_COLUMN = "schedule_cron"
        internal const val SCHEDULE_EVERY_COLUMN = "schedule_every"
        internal const val SCHEDULE_ZONE_COLUMN = "schedule_zone"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = SCHEDULE_TABLE

    // add the after, cron and every column
    override val prepareStatementMap: Map<String, (PreparedStatement, ScheduleOutboxModel, Int) -> Unit> =
        super.prepareStatementMap + (
            SCHEDULE_AFTER_COLUMN to { stmt: PreparedStatement, entity: ScheduleOutboxModel, idx: Int ->
                stmt.setString(idx, entity.scheduleAfter)
            }) + (
            SCHEDULE_EVERY_COLUMN to { stmt: PreparedStatement, entity: ScheduleOutboxModel, idx: Int ->
                stmt.setString(idx, entity.scheduleEvery)
            }) + (
            SCHEDULE_CRON_COLUMN to { stmt: PreparedStatement, entity: ScheduleOutboxModel, idx: Int ->
                stmt.setString(idx, entity.scheduleCron)
            }) + (
            SCHEDULE_ZONE_COLUMN to { stmt: PreparedStatement, entity: ScheduleOutboxModel, idx: Int ->
                stmt.setString(idx, entity.scheduleZone)
            })

    override fun createModel(rs: ResultSet) = ScheduleOutboxModel(
        id = IDV7(getUuid(rs, ID_COLUMN)!!),
        instanceMessage = rs.getInstanceMessage()!!,
        scheduleAfter = rs.getString(SCHEDULE_AFTER_COLUMN),
        scheduleEvery = rs.getString(SCHEDULE_EVERY_COLUMN),
        scheduleCron = rs.getString(SCHEDULE_CRON_COLUMN),
        scheduleZone = rs.getString(SCHEDULE_ZONE_COLUMN),
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN),
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN),
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN),
    )

    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): ScheduleOutboxModel? =
        findWithWorkflowId(workflowId, connection).firstOrNull()
}
