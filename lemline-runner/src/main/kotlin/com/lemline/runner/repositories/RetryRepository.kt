// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val RETRY_TABLE = "lemline_retries"

/**
 * Repository for managing retry messages in the outbox pattern.
 * This repository handles the persistence and retrieval of retry messages,
 * which are used to implement retry logic for failed operations in workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see RetryOutboxModel for the message model
 * @see com.lemline.runner.outbox.OutboxRelay for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
class RetryRepository : OutboxRepository<RetryOutboxModel>() {

    companion object {
        internal const val MESSAGE_COLUMN = "message"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RETRY_TABLE

    // add the message colum
    override val prepareStatementMap: Map<String, (PreparedStatement, RetryOutboxModel, Int) -> Unit> =
        super.prepareStatementMap + (
            MESSAGE_COLUMN to { stmt: PreparedStatement, entity: RetryOutboxModel, idx: Int ->
                stmt.setString(idx, entity.message)
            })

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = RetryOutboxModel(
        id = getUuid(rs, ID_COLUMN),
        instance = rs.getInstanceMessage(),
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxLastError = rs.getString(OUTBOX_LAST_ERROR_COLUMN),
        message = rs.getString(MESSAGE_COLUMN),
    )
}
