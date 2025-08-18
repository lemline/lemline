// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.SCHEDULE_TABLE
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

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
 * @see WaitModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
internal class ScheduleRepository : OutboxRepository<ScheduleModel>() {

    companion object {
        internal const val SCHEDULE_AFTER_COLUMN = "schedule_after"
        internal const val SCHEDULE_CRON_COLUMN = "schedule_cron"
        internal const val SCHEDULE_EVERY_COLUMN = "schedule_every"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = SCHEDULE_TABLE

    // add the after, cron and every column
    override val entityMap: Map<String, (PreparedStatement, ScheduleModel, Int) -> Unit> = super.entityMap + (
        SCHEDULE_AFTER_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleAfter)
        }) + (
        SCHEDULE_CRON_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleCron)
        }) + (
        SCHEDULE_EVERY_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleEvery)
        })

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = ScheduleModel(
        id = rs.getString(ID_COLUMN),

        workflowId = rs.getString(WORKFLOW_ID_COLUMN),
        workflowName = rs.getString(WORKFLOW_NAME_COLUMN),
        workflowVersion = rs.getString(WORKFLOW_VERSION_COLUMN),
        workflowPosition = rs.getString(WORKFLOW_POSITION_COLUMN),
        workflowState = rs.getString(WORKFLOW_STATE_COLUMN),

        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxLastError = rs.getString(OUTBOX_LAST_ERROR_COLUMN),

        scheduleAfter = rs.getString(SCHEDULE_AFTER_COLUMN),
        scheduleCron = rs.getString(SCHEDULE_CRON_COLUMN),
        scheduleEvery = rs.getString(SCHEDULE_EVERY_COLUMN)
    )
}
