// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.repositories.helpers.ColumnBindings
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.ops.CREATED_AT_COLUMN
import com.lemline.runner.repositories.ops.CrudRepository
import com.lemline.runner.repositories.ops.IdRepository
import com.lemline.runner.repositories.ops.OutboxRepository
import com.lemline.runner.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.repositories.ops.getInstant
import com.lemline.runner.repositories.with.WithIdRepository
import com.lemline.runner.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val LISTENER_EVENT_TABLE = "lemline_listener_events"

/**
 * Repository for managing listener event accumulation.
 * Uses composition pattern with column bindings.
 *
 * @see ListenerEventModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerEventRepository : CrudRepository<ListenerEventModel>(),
    WithIdRepository<ListenerEventModel>,
    WithOutboxRepository<ListenerEventModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    // Composed operations - initialized lazily to ensure databaseManager is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseManager) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)


    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    companion object {
        const val ID_COLUMN = "id"
        const val LISTENER_ID_COLUMN = "listener_id"
        const val FILTER_INDEX_COLUMN = "filter_index"
        const val CLOUDEVENT_ID_COLUMN = "cloudevent_id"
        const val EVENT_COLUMN = "event"

        // Foreach outbox columns
        const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"
        const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"
        const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"
        const val OUTBOX_ERROR_CLASS_COLUMN = "outbox_error_class"
        const val OUTBOX_ERROR_MESSAGE_COLUMN = "outbox_error_message"
        const val OUTBOX_ERROR_STACKTRACE_COLUMN = "outbox_error_stacktrace"
        const val OUTBOX_COMPLETED_AT_COLUMN = "outbox_completed_at"
        const val OUTBOX_FAILED_AT_COLUMN = "outbox_failed_at"

        // Foreach iteration tracking columns
        const val ITERATION_INDEX_COLUMN = "iteration_index"
        const val ITERATION_OUTPUT_COLUMN = "iteration_output"
    }
    
    override val tableName = LISTENER_EVENT_TABLE

    override val columns: ColumnBindings<ListenerEventModel> by lazy {
        ColumnBindingsBuilder<ListenerEventModel>().apply {
            // Key column
            key(ID_COLUMN) { stmt, entity, idx -> setIDV7(stmt, idx, entity.id) }

            // Other columns
            column(LISTENER_ID_COLUMN) { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenerId) }
            column(FILTER_INDEX_COLUMN) { stmt, entity, idx ->
                entity.filterIndex?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
            column(CLOUDEVENT_ID_COLUMN) { stmt, entity, idx ->
                entity.cloudEventId?.let { stmt.setString(idx, it) } ?: stmt.setNull(idx, Types.VARCHAR)
            }
            column(EVENT_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.event) }

            // Foreach outbox columns
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
            column(OUTBOX_ERROR_CLASS_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.outboxErrorClass)
            }
            column(OUTBOX_ERROR_MESSAGE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.outboxErrorMessage)
            }
            column(OUTBOX_ERROR_STACKTRACE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.outboxErrorStackTrace)
            }
            column(OUTBOX_COMPLETED_AT_COLUMN) { stmt, entity, idx ->
                entity.outboxCompletedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column(OUTBOX_FAILED_AT_COLUMN) { stmt, entity, idx ->
                entity.outboxFailedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }

            // Foreach iteration tracking
            column(ITERATION_INDEX_COLUMN) { stmt, entity, idx ->
                entity.iterationIndex?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
            column(ITERATION_OUTPUT_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.iterationOutput)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet): ListenerEventModel = ListenerEventModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!,
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN).takeIf { !rs.wasNull() },
        cloudEventId = rs.getString(CLOUDEVENT_ID_COLUMN),
        event = rs.getString(EVENT_COLUMN),
        createdAt = rs.getInstant(CREATED_AT_COLUMN),
        // Foreach outbox columns
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN),
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN),
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN),
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN),
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN),
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN),
        outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN),
        // Foreach iteration tracking
        iterationIndex = rs.getInt(ITERATION_INDEX_COLUMN).takeIf { !rs.wasNull() },
        iterationOutput = rs.getString(ITERATION_OUTPUT_COLUMN)
    )

    /**
     * Finds all events for a listener, ordered by filter index.
     */
    suspend fun findByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): List<ListenerEventModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val findByListenerIdSql by lazy {
        "SELECT * FROM $tableName WHERE $LISTENER_ID_COLUMN = ? ORDER BY $FILTER_INDEX_COLUMN"
    }

    /**
     * Batch finds all events for multiple listeners in a single query.
     */
    suspend fun batchFindByListenerIds(
        listenerIds: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, List<ListenerEventModel>> {
        if (listenerIds.isEmpty()) return emptyMap()

        return withConnection(connection) { conn ->
            val placeholders = listenerIds.joinToString(", ") { "?" }
            val sql = """
                SELECT * FROM $tableName
                WHERE $LISTENER_ID_COLUMN IN ($placeholders)
                ORDER BY $LISTENER_ID_COLUMN, $FILTER_INDEX_COLUMN
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                listenerIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildMap<IDV7, MutableList<ListenerEventModel>> {
                        while (rs.next()) {
                            val event = createModel(rs)
                            getOrPut(event.listenerId) { mutableListOf() }.add(event)
                        }
                    }
                }
            }
        }
    }

    /**
     * Batch counts events for multiple listeners in a single query.
     */
    suspend fun batchCountByListenerIds(
        listenerIds: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, Int> {
        if (listenerIds.isEmpty()) return emptyMap()

        return withConnection(connection) { conn ->
            val placeholders = listenerIds.joinToString(", ") { "?" }
            val sql = """
                SELECT $LISTENER_ID_COLUMN, COUNT(*) as cnt
                FROM $tableName
                WHERE $LISTENER_ID_COLUMN IN ($placeholders)
                GROUP BY $LISTENER_ID_COLUMN
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                listenerIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!
                            val count = rs.getInt("cnt")
                            put(listenerId, count)
                        }
                    }
                }
            }
        }
    }

    /**
     * Counts events for a single listener.
     */
    suspend fun countByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(countByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private val countByListenerIdSql by lazy {
        "SELECT COUNT(*) FROM $tableName WHERE $LISTENER_ID_COLUMN = ?"
    }

    /**
     * Deletes all events for a listener.
     */
    suspend fun deleteByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeUpdate()
        }
    }

    private val deleteByListenerIdSql by lazy {
        "DELETE FROM $tableName WHERE $LISTENER_ID_COLUMN = ?"
    }

    /**
     * Bulk inserts events for all listeners matching the given query keys.
     */
    suspend fun bulkInsertEventsForKeys(
        keys: List<ListenerQueryKey>,
        cloudEventId: String,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val selectSql = """
                SELECT ${databaseManager.randomUuid()}, l.id, NULL, ?, ?, CURRENT_TIMESTAMP
                FROM $LISTENER_TABLE l
                WHERE l.outbox_delayed_until IS NULL
                  AND l.outbox_completed_at IS NULL
                  AND l.outbox_failed_at IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
            """.trimIndent()

            val sql = databaseManager.insertIgnoreSelect(
                tableName = tableName,
                columns = "$ID_COLUMN, $LISTENER_ID_COLUMN, $FILTER_INDEX_COLUMN, $CLOUDEVENT_ID_COLUMN, $EVENT_COLUMN, $CREATED_AT_COLUMN",
                selectSql = selectSql
            )

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, cloudEventId)
                stmt.setString(idx++, eventJson)
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Bulk inserts events for ALL strategy listeners matching the given query keys.
     */
    suspend fun bulkInsertEventsForAllStrategy(
        keys: List<ListenerQueryKey>,
        filterIndex: Int,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val selectSql = """
                SELECT ${databaseManager.randomUuid()}, l.id, ?, NULL, ?, CURRENT_TIMESTAMP
                FROM $LISTENER_TABLE l
                WHERE l.outbox_delayed_until IS NULL
                  AND l.outbox_completed_at IS NULL
                  AND l.outbox_failed_at IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
            """.trimIndent()

            val sql = databaseManager.insertIgnoreSelect(
                tableName = tableName,
                columns = "$ID_COLUMN, $LISTENER_ID_COLUMN, $FILTER_INDEX_COLUMN, $CLOUDEVENT_ID_COLUMN, $EVENT_COLUMN, $CREATED_AT_COLUMN",
                selectSql = selectSql
            )

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setInt(idx++, filterIndex)
                stmt.setString(idx++, eventJson)
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    // ========================================
    // Foreach Outbox Methods
    // ========================================

    /**
     * Finds the next pending event for a listener (FIFO order).
     */
    suspend fun findNextPending(
        listenerId: IDV7,
        connection: Connection? = null
    ): ListenerEventModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findNextPendingSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findNextPendingSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $LISTENER_ID_COLUMN = ?
          AND $OUTBOX_SCHEDULED_FOR_COLUMN IS NOT NULL
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        ORDER BY $CREATED_AT_COLUMN ASC
        LIMIT 1
        """.trimIndent()
    }

    /**
     * Marks an event as ready for foreach processing.
     */
    suspend fun markReadyForProcessing(
        id: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = Timestamp.from(Clock.System.now().toJavaInstant())
        conn.prepareStatement(markReadyForProcessingSql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setTimestamp(2, now)
            setIDV7(stmt, 3, id)
            stmt.executeUpdate()
        }
    }

    private val markReadyForProcessingSql by lazy {
        """
        UPDATE $tableName
        SET $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        """.trimIndent()
    }

    /**
     * Marks an event's foreach processing as completed.
     */
    suspend fun markForeachCompleted(
        id: IDV7,
        output: String,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = Timestamp.from(Clock.System.now().toJavaInstant())
        conn.prepareStatement(markForeachCompletedSql).use { stmt ->
            stmt.setString(1, output)
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
            setIDV7(stmt, 4, id)
            stmt.executeUpdate()
        }
    }

    private val markForeachCompletedSql by lazy {
        """
        UPDATE $tableName
        SET $ITERATION_OUTPUT_COLUMN = ?,
            $OUTBOX_COMPLETED_AT_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Marks an event's foreach outbox as failed.
     */
    suspend fun markOutboxFailed(
        id: IDV7,
        errorClass: String?,
        errorMessage: String?,
        errorStackTrace: String?,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = Timestamp.from(Clock.System.now().toJavaInstant())
        conn.prepareStatement(markForeachFailedSql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setString(2, errorClass)
            stmt.setString(3, errorMessage)
            stmt.setString(4, errorStackTrace)
            stmt.setTimestamp(5, now)
            setIDV7(stmt, 6, id)
            stmt.executeUpdate()
        }
    }

    private val markForeachFailedSql by lazy {
        """
        UPDATE $tableName
        SET $OUTBOX_FAILED_AT_COLUMN = ?,
            $OUTBOX_ERROR_CLASS_COLUMN = ?,
            $OUTBOX_ERROR_MESSAGE_COLUMN = ?,
            $OUTBOX_ERROR_STACKTRACE_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Gets all completed iteration outputs for a listener.
     */
    suspend fun getAllOutputs(
        listenerId: IDV7,
        connection: Connection? = null
    ): List<String> = withConnection(connection) { conn ->
        conn.prepareStatement(getAllOutputsSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.getString(ITERATION_OUTPUT_COLUMN)?.let { add(it) }
                    }
                }
            }
        }
    }

    private val getAllOutputsSql by lazy {
        """
        SELECT $ITERATION_OUTPUT_COLUMN FROM $tableName
        WHERE $LISTENER_ID_COLUMN = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
        ORDER BY $CREATED_AT_COLUMN ASC
        """.trimIndent()
    }

    /**
     * Finds events ready for foreach outbox processing.
     */
    suspend fun findReadyForForeachProcessing(
        limit: Int,
        connection: Connection? = null
    ): List<ListenerEventModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findReadyForForeachProcessingSql).use { stmt ->
            val now = Timestamp.from(Clock.System.now().toJavaInstant())
            stmt.setTimestamp(1, now)
            stmt.setInt(2, limit)
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val findReadyForForeachProcessingSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $OUTBOX_SCHEDULED_FOR_COLUMN IS NOT NULL
          AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        ORDER BY $CREATED_AT_COLUMN ASC
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    /**
     * Finds an event by listener ID and iteration index.
     */
    suspend fun findByListenerIdAndIterationIndex(
        listenerId: IDV7,
        iterationIndex: Int,
        connection: Connection? = null
    ): ListenerEventModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findByListenerIdAndIterationIndexSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.setInt(2, iterationIndex)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByListenerIdAndIterationIndexSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $LISTENER_ID_COLUMN = ?
          AND $ITERATION_INDEX_COLUMN = ?
        """.trimIndent()
    }
}
