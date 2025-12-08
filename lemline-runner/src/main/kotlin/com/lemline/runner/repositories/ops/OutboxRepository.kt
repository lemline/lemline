// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.ops

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WithOutbox
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.with.WithOutboxRepository
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val OUTBOX_COMPLETED_AT_COLUMN = "outbox_completed_at"
const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"
const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"
const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"
const val OUTBOX_FAILED_AT_COLUMN = "outbox_failed_at"
const val OUTBOX_ERROR_CLASS_COLUMN = "outbox_error_class"
const val OUTBOX_ERROR_MESSAGE_COLUMN = "outbox_error_message"
const val OUTBOX_ERROR_STACKTRACE_COLUMN = "outbox_error_stacktrace"

/**
 * Helper class providing outbox-related database operations.
 * Use this via composition instead of inheriting from OutboxRepository.
 *
 * @param T The entity type, must implement [WithOutbox]
 * @param tableName The database table name
 * @param createModel Function to create a model from a ResultSet
 * @param databaseManager The database manager for connections
 */
class OutboxRepository<T : WithOutbox>(
    private val tableName: String,
    private val createModel: (ResultSet) -> T,
    private val databaseManager: DatabaseManager
) : WithOutboxRepository<T> {

    companion object;

    /**
     * Finds and locks entities that are ready to be processed.
     * Uses FOR UPDATE SKIP LOCKED for parallel processing safety.
     *
     * Entities are ready to process if:
     * - Not completed (outbox_completed_at IS NULL)
     * - Not failed (outbox_failed_at IS NULL)
     * - Delay has passed (outbox_delayed_until <= NOW)
     * - Haven't exceeded max attempts (outbox_attempt_count < maxAttempts)
     *
     * @param maxAttempts Maximum number of retry attempts allowed
     * @param limit Maximum number of entities to retrieve
     * @param connection Optional database connection to use
     * @return List of locked entities ready for processing
     */
    override suspend fun findEntitiesToProcess(
        maxAttempts: Int,
        limit: Int,
        connection: Connection?
    ): List<T> = databaseManager.withConnection(connection) { conn ->
        conn.prepareStatement(findEntitiesToProcessSQL).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
            stmt.setInt(2, maxAttempts)
            stmt.setInt(3, limit)
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val findEntitiesToProcessSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NULL
              AND $OUTBOX_FAILED_AT_COLUMN IS NULL
              AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
              AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
              AND $OUTBOX_ATTEMPT_COUNT_COLUMN < ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    private fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}

/**
 * Extension function to read outbox fields from a ResultSet into a WithOutbox entity.
 */
fun <T : WithOutbox> T.readOutboxFields(rs: ResultSet): T = apply {
    outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN)
    outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN)
    outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
    outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
    outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
    outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
    outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
}

/**
 * Extension function to add outbox columns to ColumnBindingsBuilder.
 * Adds all outbox-related column bindings for entities implementing WithOutbox.
 *
 * Columns added:
 * - outbox_scheduled_for
 * - outbox_delayed_until
 * - outbox_attempt_count
 * - outbox_failed_at
 * - outbox_error_class
 * - outbox_error_message
 * - outbox_error_stacktrace
 *
 * Usage:
 * ```kotlin
 * override val columns by lazy {
 *     ColumnBindingsBuilder<MyModel>().apply {
 *         key("id") { stmt, entity, idx -> setIDV7(stmt, idx, entity.id) }
 *         outboxColumns()  // adds all 7 outbox columns
 *         // ... other columns
 *     }.build()
 * }
 * ```
 */
fun <T : WithOutbox> ColumnBindingsBuilder<T>.outboxColumns() {
    column(OUTBOX_COMPLETED_AT_COLUMN) { stmt, entity, idx ->
        entity.outboxCompletedAt?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
    column(OUTBOX_SCHEDULED_FOR_COLUMN) { stmt, entity, idx ->
        entity.outboxScheduledFor?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
    column(OUTBOX_DELAYED_UNTIL_COLUMN) { stmt, entity, idx ->
        entity.outboxDelayedUntil?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
    column(OUTBOX_ATTEMPT_COUNT_COLUMN) { stmt, entity, idx ->
        stmt.setInt(idx, entity.outboxAttemptCount)
    }
    column(OUTBOX_FAILED_AT_COLUMN) { stmt, entity, idx ->
        entity.outboxFailedAt?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
    column(OUTBOX_ERROR_CLASS_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.outboxErrorClass)
    }
    column(OUTBOX_ERROR_MESSAGE_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.outboxErrorMessage)
    }
    column(OUTBOX_ERROR_STACKTRACE_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.outboxErrorStackTrace)
    }
}
