// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.IDV7
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

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RETRY_TABLE

    companion object {
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    // add the error
    override val prepareStatementMap: Map<String, (PreparedStatement, RetryOutboxModel, Int) -> Unit> =
        super.prepareStatementMap + (
            ERROR_REASON_COLUMN to { stmt: PreparedStatement, entity: RetryOutboxModel, idx: Int ->
                stmt.setString(idx, entity.errorReason)
            }) + (
            ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: RetryOutboxModel, idx: Int ->
                stmt.setString(idx, entity.errorClass)
            }) + (
            ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: RetryOutboxModel, idx: Int ->
                stmt.setString(idx, entity.errorMessage)
            }) + (
            ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: RetryOutboxModel, idx: Int ->
                stmt.setString(idx, entity.errorStackTrace)
            })

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = RetryOutboxModel(
        id = IDV7(getUuid(rs, ID_COLUMN)),
        instanceMessage = rs.getInstanceMessage()!!,
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN),
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN),
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN),
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN)
    )
}
