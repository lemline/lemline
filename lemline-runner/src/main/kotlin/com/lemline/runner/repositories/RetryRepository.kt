// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.RetryModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
 * @see RetryModel for the message model
 * @see com.lemline.runner.outbox.OutboxRelay for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class RetryRepository : OutboxRepository<RetryModel>() {

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
    override val prepareStatementMap: Map<String, (PreparedStatement, RetryModel, Int) -> Unit> =
        super.prepareStatementMap + (
            ERROR_REASON_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
                stmt.setString(idx, entity.errorReason)
            }) + (
            ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
                stmt.setString(idx, entity.errorClass)
            }) + (
            ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
                stmt.setString(idx, entity.errorMessage)
            }) + (
            ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
                stmt.setString(idx, entity.errorStackTrace)
            })

    override fun createModel(rs: ResultSet) = RetryModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.RetryScheduled>()!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN),
    ).apply {
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN)
        outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN)
    }
}
