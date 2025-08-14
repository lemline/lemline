// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WAIT_TABLE
import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet

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
internal class WaitRepository : OutboxRepository<WaitModel>() {
    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = WAIT_TABLE

    override fun createModel(rs: ResultSet) = WaitModel(
        workflowId = rs.getString("id"),
        message = rs.getString("message"),
        status = OutBoxStatus.valueOf(rs.getString("status")),
        scheduledFor = rs.getInstant("scheduled_for"),
        delayedUntil = rs.getInstant("delayed_until"),
        attemptCount = rs.getInt("attempt_count"),
        lastError = rs.getString("last_error"),
    )
}
