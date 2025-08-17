// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.RUN_WORKFLOW_TABLE
import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutboxProcessor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

/**
 * Repository for managing run messages in the outbox pattern.
 * This repository handles the persistence and retrieval of run messages,
 * which are used to implement run logic for workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see RunWorkflowModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
internal class RunWorkflowRepository : OutboxRepository<RunWorkflowModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RUN_WORKFLOW_TABLE

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = RunWorkflowModel(
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
    )
}
