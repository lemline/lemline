// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.IDV7
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutboxRelay
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

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
 * @see WaitOutboxModel for the message model
 * @see OutboxRelay for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
internal class WaitRepository : OutboxRepository<WaitOutboxModel>() {
    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = WAIT_TABLE

    @ExperimentalTime
    override fun createModel(rs: ResultSet) = WaitOutboxModel(
        id = IDV7(getUuid(rs, ID_COLUMN)!!),
        instanceMessage = rs.getInstanceMessage()!!,
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN),
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN),
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN),
    )
}
