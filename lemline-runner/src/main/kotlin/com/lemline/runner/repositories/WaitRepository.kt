// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.AbstractOutbox
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val WAIT_TABLE = "lemline_waits"

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
 * @see AbstractOutbox for the processing logic
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class WaitRepository : OutboxRepository<WaitModel>() {
    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = WAIT_TABLE

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = WaitModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.WaitStarted>()!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
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
