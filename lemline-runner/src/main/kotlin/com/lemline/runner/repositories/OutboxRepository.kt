// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.FAILED
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Abstract repository for managing entities that follow the outbox pattern.
 * Provides functionality for storing, retrieving and processing messages
 * from the outbox table in a reliable manner.
 *
 * This repository facilitates mechanisms such as:
 * - Fetching and locking messages to process.
 * - Fetching and locking messages to delete.
 * - Customizable SQL prepared statement mappings for outbox-related fields.
 *
 * This class extends `WithInstanceRepository` to apply repository operations
 * for models conforming to the `OutboxModel` interface.
 *
 * @param T The type of entity managed by this repository, extending OutboxModel.
 */
@Suppress("unused")
@ExperimentalTime
abstract class OutboxRepository<T : OutboxModel> : WithInstanceRepository<T>() {

    @Inject
    lateinit var failureRepository: FailureRepository

    companion object {
        internal const val OUTBOX_STATUS_COLUMN = "outbox_status"
        internal const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"
        internal const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"
        internal const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"
        internal const val OUTBOX_ERROR_CLASS_COLUMN = "outbox_error_class"
        internal const val OUTBOX_ERROR_MESSAGE_COLUMN = "outbox_error_message"
        internal const val OUTBOX_ERROR_STACKTRACE_COLUMN = "outbox_error_stacktrace"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            OUTBOX_STATUS_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outBoxStatus.name)
            },
            OUTBOX_SCHEDULED_FOR_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setTimestamp(idx, entity.outboxScheduledFor?.toJavaInstant()?.let { Timestamp.from(it) })
            },
            OUTBOX_DELAYED_UNTIL_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setTimestamp(idx, entity.outboxDelayedUntil?.toJavaInstant()?.let { Timestamp.from(it) })
            },
            OUTBOX_ATTEMPT_COUNT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setInt(idx, entity.outboxAttemptCount)
            },
            OUTBOX_ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outboxErrorClass)
            },
            OUTBOX_ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outboxErrorMessage)
            },
            OUTBOX_ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outboxErrorStackTrace)
            }
        )
    }

    suspend fun retryById(id: UUID, connection: Connection? = null): Int = withConnection(connection) { conn ->
        findById(id, conn)?.let { entity ->
            // if the outbox was failed, delete the failure entry
            if (entity.outBoxStatus == FAILED) {
                failureRepository.deleteById(FailureModel.from(entity).id, conn)
            }
            entity.outBoxStatus = PENDING
            update(entity, conn)
        } ?: 0
    }

    /**
     * Finds and locks messages that are ready to be processed.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param limit Maximum number of messages to retrieve
     * @param maxAttempts Maximum number of retry attempts allowed
     * @return List of locked messages ready for processing
     */
    suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection? = null): List<T> =
        withConnection(connection) {
            it.prepareStatement(findEntitiesToProcessSQL).use { stmt ->
                stmt.apply {
                    setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
                    setInt(2, maxAttempts)
                    setInt(3, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findEntitiesToProcessSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = '$PENDING'
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            AND $OUTBOX_ATTEMPT_COUNT_COLUMN < ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    /**
     * Finds and locks messages that are ready to be deleted.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param cutoffDate Messages older than this date will be selected
     * @param limit Maximum number of messages to retrieve
     * @return List of locked messages ready for deletion
     */
    suspend fun findEntitiesToDelete(cutoffDate: Instant, limit: Int, connection: Connection? = null): List<T> =
        withConnection(connection) {
            it.prepareStatement(findEntitiesToDeleteSQL).use { stmt ->
                stmt.apply {
                    setTimestamp(1, Timestamp.from(cutoffDate.toJavaInstant()))
                    setInt(2, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findEntitiesToDeleteSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = '$SENT'
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }
}
