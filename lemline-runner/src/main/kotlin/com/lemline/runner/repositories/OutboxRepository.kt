// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.OutboxModel
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract repository for managing entities that follow the outbox pattern.
 * Extends CleanableRepository to inherit cleanup functionality and adds outbox-specific
 * processing capabilities (retry logic, error tracking, failure timestamps).
 *
 * This repository provides:
 * - Message processing with retry logic (findEntitiesToProcess)
 * - Cleanup functionality (inherited from CleanableRepository)
 * - Customizable SQL prepared statement mappings for outbox retry/error/failure fields
 *
 * @param T The type of entity managed by this repository, extending OutboxModel.
 * @see CleanerRepository for cleanup functionality
 * @see OutboxModel for the entity model
 */
@Suppress("unused")
@ExperimentalTime
@ExperimentalSerializationApi
abstract class OutboxRepository<T : OutboxModel> : CleanerRepository<T>() {

    @Inject
    lateinit var failureRepository: FailureRepository

    companion object Companion {
        /**
         * Column name for scheduled for timestamp
         */
        internal const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"

        /**
         * Column name for delayed until timestamp
         */
        internal const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"

        /**
         * Column name for attempt count
         */
        internal const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"

        /**
         * Column name for failed at timestamp
         */
        internal const val OUTBOX_FAILED_AT_COLUMN = "outbox_failed_at"

        /**
         * Column name for error class
         */
        internal const val OUTBOX_ERROR_CLASS_COLUMN = "outbox_error_class"

        /**
         * Column name for error message
         */
        internal const val OUTBOX_ERROR_MESSAGE_COLUMN = "outbox_error_message"

        /**
         * Column name for error stacktrace
         */
        internal const val OUTBOX_ERROR_STACKTRACE_COLUMN = "outbox_error_stacktrace"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            // Only add outbox-specific fields; cleanup fields are inherited from CleanableRepository
            OUTBOX_SCHEDULED_FOR_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                entity.outboxScheduledFor?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
            },
            OUTBOX_DELAYED_UNTIL_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                entity.outboxDelayedUntil?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
            },
            OUTBOX_ATTEMPT_COUNT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setInt(idx, entity.outboxAttemptCount)
            },
            OUTBOX_FAILED_AT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                entity.outboxFailedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
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

//    suspend fun retryById(id: IDV7, connection: Connection? = null): Int = withConnection(connection) { conn ->
//        findById(id, conn)?.let { entity ->
//            // if the outbox was failed, delete the failure entry
//            if (entity.outBoxStatus == FAILED) {
//                failureRepository.deleteById(FailureModel.from(entity).id, conn)
//            }
//            entity.outBoxStatus = PENDING
//            update(entity, conn)
//        } ?: 0
//    }

    /**
     * Finds and locks messages that are ready to be processed.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * Messages are ready to process if:
     * - Not completed (outbox_completed_at IS NULL)
     * - Not failed (outbox_failed_at IS NULL)
     * - Either no delay or delay has passed (outbox_delayed_until IS NULL OR <= NOW)
     * - Haven't exceeded max attempts (outbox_attempt_count < maxAttempts)
     *
     * @param maxAttempts Maximum number of retry attempts allowed
     * @param limit Maximum number of messages to retrieve
     * @param connection Optional database connection to use
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

}
