// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.PARENT_TABLE
import com.lemline.runner.models.ParentModel
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
 * @see ParentModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
internal class ParentRepository : OutboxRepository<ParentModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = PARENT_TABLE

    override fun createModel(rs: ResultSet) = ParentModel(
        id = getUuid(rs, ID_COLUMN),
        instance = rs.getInstanceMessage(),
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxLastError = rs.getString(OUTBOX_LAST_ERROR_COLUMN),
    )
}
