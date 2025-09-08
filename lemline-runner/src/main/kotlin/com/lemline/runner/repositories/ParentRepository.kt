// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutboxRelay
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val PARENT_TABLE = "lemline_parents"

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
 * @see ParentOutboxModel for the message model
 * @see OutboxRelay for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentRepository : OutboxRepository<ParentOutboxModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = PARENT_TABLE

    override fun createModel(rs: ResultSet) = ParentOutboxModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage()!!,
        outBoxStatus = OutBoxStatus.valueOf(rs.getString(OUTBOX_STATUS_COLUMN)),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
    ).apply {
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
    }
}
