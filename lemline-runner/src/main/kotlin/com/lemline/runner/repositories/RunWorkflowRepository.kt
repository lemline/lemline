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
internal class RunWorkflowRepository : OutboxRepository<RunWorkflowModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RUN_WORKFLOW_TABLE

    override fun createModel(rs: ResultSet) = RunWorkflowModel(
        id = rs.getString("id"),
        message = rs.getString("message"),
        status = OutBoxStatus.valueOf(rs.getString("status")),
        delayedUntil = rs.getInstant("delayed_until"),
        attemptCount = rs.getInt("attempt_count"),
        lastError = rs.getString("last_error"),
    )
}
