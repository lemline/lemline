// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.capabilities

import com.lemline.common.values.IDV7
import com.lemline.runner.models.bases.OptionalOutboxColumns
import com.lemline.runner.models.bases.OutboxColumns
import com.lemline.runner.models.bases.OutboxColumnsBase
import com.lemline.runner.models.bases.runDelayedUntil
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.bases.Repository
import com.lemline.runner.repositories.bases.getInstant
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

internal const val RUN_DELAYED_UNTIL_COLUMN = "run_delayed_until"
internal const val RUN_ATTEMPT_COUNT_COLUMN = "run_attempt_count"
internal const val RUN_LAST_ERROR_CLASS_COLUMN = "run_last_error_class"
internal const val RUN_LAST_ERROR_MESSAGE_COLUMN = "run_last_error_message"
internal const val RUN_LAST_ERROR_STACKTRACE_COLUMN = "run_last_error_stacktrace"

/**
 * Capabilities of [OutboxColumns] and [OptionalOutboxColumns]
 */
interface OutboxCapabilities<T : OutboxColumnsBase> {
    /**
     * Finds the entities that need to be sent based on the specified criteria.
     *
     * @param maxAttempts The maximum number of attempts an entity can have before being excluded from the result.
     * @param limit The maximum number of entities to retrieve.
     * @param connection An optional database connection to use for the query. If not provided, a new connection may be used.
     * @return A list of entities that satisfy the criteria for sending.
     */
    suspend fun findEntitiesToSend(maxAttempts: Int, limit: Int, connection: Connection? = null): List<T>

    /**
     * Retries a specific operation for the entity identified by the given ID.
     *
     * @param id The unique identifier of the entity for which the retry action should be performed.
     * @param connection An optional database connection to use for the operation. If not provided, a new connection may be used.
     * @return The number of rows affected by the retry operation. Typically, this will be 1 if the operation was successful, or 0 if no matching entity was found.
     */
    suspend fun retryById(id: IDV7, connection: Connection? = null): Int
}

/**
 * Implementation of capabilities for [OutboxColumns]
 */
class OutboxCapable<T : OutboxColumns>(
    repository: Repository<T>,
    failureRepository: FailureRepository
) : OutboxCapableBase<T>(repository, failureRepository) {
    val ResultSet.runDelayedUntil: Instant get() = getInstant(RUN_DELAYED_UNTIL_COLUMN)!!
}

/**
 * Implementation of capabilities for [OptionalOutboxColumns]
 */
class OptionalOutboxCapable<T : OptionalOutboxColumns>(
    repository: Repository<T>,
    failureRepository: FailureRepository
) : OutboxCapableBase<T>(repository, failureRepository) {
    val ResultSet.runDelayedUntil: Instant? get() = getInstant(RUN_DELAYED_UNTIL_COLUMN)
}

abstract class OutboxCapableBase<T : OutboxColumnsBase>(
    private val repository: Repository<T>,
    private val failureRepository: FailureRepository
) : OutboxCapabilities<T> {

    val ResultSet.runAttemptCount: Int get() = getInt(RUN_ATTEMPT_COUNT_COLUMN)
    val ResultSet.runLastErrorClass: String? get() = getString(RUN_LAST_ERROR_CLASS_COLUMN)
    val ResultSet.runLastErrorMessage: String? get() = getString(RUN_LAST_ERROR_MESSAGE_COLUMN)
    val ResultSet.runLastErrorStackTrace: String? get() = getString(RUN_LAST_ERROR_STACKTRACE_COLUMN)

    private val idCapable = IdCapable(repository)

    val mapping: Map<String, (PreparedStatement, T, Int) -> Unit> = mapOf(
        RUN_DELAYED_UNTIL_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setTimestamp(idx, entity.runDelayedUntil?.toJavaInstant()?.let { Timestamp.from(it) })
        },
        RUN_ATTEMPT_COUNT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setInt(idx, entity.runAttemptCount)
        },
        RUN_LAST_ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setString(idx, entity.runLastErrorClass)
        },
        RUN_LAST_ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setString(idx, entity.runLastErrorMessage)
        },
        RUN_LAST_ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setString(idx, entity.runLastErrorStackTrace)
        }
    )

    override suspend fun retryById(id: IDV7, connection: Connection?): Int =
        repository.withConnection(connection) { conn ->
            idCapable.findById(id, conn)?.let { entity ->
                // if the outbox was failed, delete the failure entry
                if (entity.runStatus == RunStatus.FAILED) failureRepository.deleteById(id, conn)
                entity.runStatus = RunStatus.PENDING
                repository.update(entity, conn)
            } ?: 0
        }

    override suspend fun findEntitiesToSend(maxAttempts: Int, limit: Int, connection: Connection?): List<T> =
        repository.withConnection(connection) { conn ->
            conn.prepareStatement(findEntitiesToProcessSQL).use { stmt ->
                stmt.apply {
                    setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
                    setInt(2, maxAttempts)
                    setInt(3, limit)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(repository.createModel(rs))
                        }
                    }
                }
            }
        }

    private val findEntitiesToProcessSQL by lazy {
        """
            SELECT * FROM ${repository.tableName}
            WHERE $RUN_STATUS_COLUMN = '${RunStatus.PENDING}'
            AND $RUN_DELAYED_UNTIL_COLUMN <= ?
            AND $RUN_ATTEMPT_COUNT_COLUMN < ?
            ORDER BY $RUN_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }
}
