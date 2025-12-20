// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.repositories.helpers.ColumnBindings
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.ops.CREATED_AT_COLUMN
import com.lemline.runner.repositories.ops.CrudRepository
import com.lemline.runner.repositories.ops.OutboxRepository
import com.lemline.runner.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.repositories.ops.cleanupColumns
import com.lemline.runner.repositories.ops.getInstant
import com.lemline.runner.repositories.ops.readCleanupField
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
 * Repository for managing listener events with FIFO-aware foreach processing.
 *
 * ## Composite Primary Key
 *
 * This table uses `(listener_id, event_id)` as the composite primary key.
 * The `event_id` is the CloudEvent's unique identifier, providing natural idempotency.
 *
 * ## Simplified Architecture
 *
 * ALL events (for ALL strategies) are stored in this table.
 * This repository handles both event storage AND foreach outbox processing.
 *
 * ## FIFO Ordering
 *
 * Events are processed in arrival order via the `created_at` column:
 * - First event gets `outbox_delayed_until = NOW` (immediately ready)
 * - Subsequent events get `outbox_delayed_until = NULL` (waiting for FIFO turn)
 * - When an event completes, the next event's `delayed_until` is set to NOW
 *
 * @see ListenerEventModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerEventRepository : CrudRepository<ListenerEventModel>(),
    WithOutboxRepository<ListenerEventModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    // Composed operations - initialized lazily to ensure databaseManager is injected
    // Uses specialized ListenerEventOutboxRepository for FIFO-aware processing
    private val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    companion object {
        const val LISTENER_ID_COLUMN = "listener_id"
        const val EVENT_ID_COLUMN = "event_id"
        const val FILTER_INDEX_COLUMN = "filter_index"
        const val EVENT_COLUMN = "event"
        const val SORT_KEY_COLUMN = "sort_key"
        const val FOREACH_COMPLETED_COLUMN = "foreach_completed"
        const val FOREACH_OUTPUT_COLUMN = "foreach_output"

        // Standard outbox columns
        const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"
        const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"
        const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"
        const val OUTBOX_ERROR_CLASS_COLUMN = "outbox_error_class"
        const val OUTBOX_ERROR_MESSAGE_COLUMN = "outbox_error_message"
        const val OUTBOX_ERROR_STACKTRACE_COLUMN = "outbox_error_stacktrace"
        const val OUTBOX_COMPLETED_AT_COLUMN = "outbox_completed_at"
        const val OUTBOX_FAILED_AT_COLUMN = "outbox_failed_at"

        /** Creates a current timestamp for database operations. */
        private fun nowTimestamp(): Timestamp = Timestamp.from(Clock.System.now().toJavaInstant())
    }

    override val tableName = LISTENER_EVENT_TABLE

    override val columns: ColumnBindings<ListenerEventModel> by lazy {
        ColumnBindingsBuilder<ListenerEventModel>().apply {
            // Composite key columns (listener_id, event_id, filter_index)
            key(LISTENER_ID_COLUMN) { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenerId) }
            key(EVENT_ID_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.eventId) }
            key(FILTER_INDEX_COLUMN) { stmt, entity, idx -> stmt.setInt(idx, entity.filterIndex) }

            // Core columns
            column(EVENT_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.event) }
            column(FOREACH_COMPLETED_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.foreachCompleted)
            }
            column(FOREACH_OUTPUT_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.foreachOutput)
            }

            // Standard outbox columns
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

            // Cleanup
            cleanupColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet): ListenerEventModel = ListenerEventModel(
        listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!,
        eventId = rs.getString(EVENT_ID_COLUMN),
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN),
        event = rs.getString(EVENT_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
    ).apply {
        foreachCompleted = rs.getBoolean(FOREACH_COMPLETED_COLUMN)
        foreachOutput = rs.getString(FOREACH_OUTPUT_COLUMN)
        createdAt = rs.getInstant(CREATED_AT_COLUMN)
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN)
        outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN)
    }.readCleanupField(rs)

    // ========================================
    // Batch Insert Methods
    // ========================================

    /**
     * Batch inserts events for ONE/ANY strategy listeners using INSERT...SELECT.
     *
     * Uses `(listener_id, event_id, filter_index)` as composite PK for natural idempotency.
     * Filter index is set to 0 (default) for ONE/ANY strategies.
     * The UNIQUE constraint `(listener_id, filter_index)` ensures only one event per listener.
     *
     * FIFO ordering is enforced at query time (not insert time) using created_at ordering.
     * The foreach outbox processor uses ROW_NUMBER() to pick the first unprocessed event per listener.
     *
     * Completion is determined by:
     * - For foreach listeners: foreach_output IS NOT NULL (set after foreach.do completes)
     * - For non-foreach listeners: has_foreach = FALSE (no processing needed)
     *
     * Uses ON CONFLICT DO NOTHING / INSERT IGNORE for idempotency.
     *
     * @param keys Query keys for matching listeners
     * @param eventId CloudEvent ID (unique identifier from CloudEvent spec)
     * @param eventJson Serialized CloudEvent data
     * @return Number of events inserted
     */
    suspend fun batchInsertForOneAny(
        keys: List<ListenerQueryKey>,
        eventId: String,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            // Single INSERT...SELECT statement
            // Note: outbox_delayed_until is not set (defaults to NULL) - markReadyForForeach will set it
            // Note: created_at uses database default (CURRENT_TIMESTAMP)
            val columns = """
                $LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FILTER_INDEX_COLUMN, $EVENT_COLUMN,
                $FOREACH_COMPLETED_COLUMN, $FOREACH_OUTPUT_COLUMN, $OUTBOX_SCHEDULED_FOR_COLUMN
            """.trimIndent()

            val selectSql = """
                SELECT
                    l.id,                                               /* listener_id */
                    ?,                                                  /* event_id */
                    0,                                                  /* filter_index = 0 for ONE/ANY */
                    ?,                                                  /* event */
                    NOT l.has_foreach,                                  /* foreach_completed = TRUE if no foreach */
                    CASE WHEN NOT l.has_foreach THEN ? END,             /* foreach_output = event if not has foreach */
                    CASE WHEN l.has_foreach THEN CURRENT_TIMESTAMP END  /* outbox_scheduled_for = now if has foreach */
                FROM $LISTENER_TABLE l
                WHERE l.outbox_completed_at IS NULL
                  AND l.outbox_failed_at IS NULL
                  AND l.ready_at IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
            """.trimIndent()

            val insertSelectSql = databaseManager.insertIgnoreSelect(tableName, columns, selectSql)

            conn.prepareStatement(insertSelectSql).use { stmt ->
                var paramIdx = 1
                stmt.setString(paramIdx++, eventId)
                stmt.setString(paramIdx++, eventJson)
                stmt.setString(paramIdx++, eventJson) // foreach_output = event for non-foreach
                ListenerQueryKey.bindAllParameters(keys, stmt, paramIdx)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Batch inserts events for accumulating strategies (ALL/ANY+until) using INSERT...SELECT.
     *
     * Uses `(listener_id, event_id, filter_index)` as composite PK for natural idempotency.
     *
     * FIFO ordering is enforced at query time (not insert time) using created_at ordering.
     * The foreach outbox processor uses ROW_NUMBER() to pick the first unprocessed event per listener.
     *
     * Completion is determined by:
     * - For foreach listeners: foreach_output IS NOT NULL (set after foreach.do completes)
     * - For non-foreach listeners: has_foreach = FALSE (no processing needed)
     *
     * Uses ON CONFLICT DO NOTHING / INSERT IGNORE for idempotency on composite PK.
     *
     * @param keys Query keys for matching listeners (with filterIndex for ALL strategy)
     * @param eventId CloudEvent ID (unique identifier from CloudEvent spec)
     * @param eventJson Serialized CloudEvent data
     * @return Number of events inserted
     */
    suspend fun batchInsertForAccumulating(
        keys: List<ListenerQueryKey>,
        eventId: String,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            // Group by filterIndex for ALL strategy
            val byFilterIndex = keys.groupBy { it.filterIndex }

            // Build UNION ALL of all filterIndex queries
            // Include has_foreach for completion logic
            val unionParts = byFilterIndex.map { (filterIndex, queryKeys) ->
                """
                SELECT
                    l.id as listener_id,
                    ${filterIndex ?: 0} as filter_index,
                    l.has_foreach
                FROM $LISTENER_TABLE l
                WHERE l.outbox_completed_at IS NULL
                  AND l.outbox_failed_at IS NULL
                  AND l.ready_at IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(queryKeys, "l")})
                """.trimIndent()
            }

            val unionSql = unionParts.joinToString("\n                UNION ALL\n                ")

            // Single INSERT...SELECT
            // Note: outbox_delayed_until is not set (defaults to NULL) - markReadyForForeach will set it
            // Note: created_at uses database default (CURRENT_TIMESTAMP)
            val columns = """
                $LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FILTER_INDEX_COLUMN, $EVENT_COLUMN,
                $FOREACH_COMPLETED_COLUMN, $FOREACH_OUTPUT_COLUMN, $OUTBOX_SCHEDULED_FOR_COLUMN
            """.trimIndent()

            val selectSql = """
                SELECT
                    m.listener_id,                                      /* listener_id */
                    ?,                                                  /* event_id */
                    m.filter_index,                                     /* filter_index */
                    ?,                                                  /* event */
                    NOT m.has_foreach,                                  /* foreach_completed = TRUE if no foreach */
                    CASE WHEN NOT m.has_foreach THEN ? END,             /* foreach_output = event for non-foreach */
                    CASE WHEN m.has_foreach THEN CURRENT_TIMESTAMP END  /* outbox_scheduled_for = NULL if not foreach */
                FROM (
                    $unionSql
                ) m
            """.trimIndent()

            val insertSelectSql = databaseManager.insertIgnoreSelect(tableName, columns, selectSql)

            conn.prepareStatement(insertSelectSql).use { stmt ->
                var paramIdx = 1
                stmt.setString(paramIdx++, eventId)
                stmt.setString(paramIdx++, eventJson)
                stmt.setString(paramIdx++, eventJson) // foreach_output = event for non-foreach

                // Bind parameters for all filterIndex groups
                for ((_, queryKeys) in byFilterIndex) {
                    paramIdx = ListenerQueryKey.bindAllParameters(queryKeys, stmt, paramIdx)
                }

                stmt.executeUpdate()
            }
        }
    }

    // ========================================
    // Foreach Processing Methods
    // ========================================

    /**
     * Finds the event currently being processed for a listener.
     * Processing events have:
     * - outbox_attempt_count > 0: Has been picked up by outbox at least once
     * - outbox_completed_at IS NULL: Not yet completed (waiting for ListenForEachCompleted)
     * - outbox_failed_at IS NULL: Not failed
     *
     * Note: outbox_delayed_until is set to far future to prevent re-pickup,
     * so we don't filter on it here.
     */
    suspend fun findProcessingEvent(
        listenerId: IDV7,
        connection: Connection? = null
    ): ListenerEventModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findProcessingEventSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findProcessingEventSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $LISTENER_ID_COLUMN = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
          AND $OUTBOX_ATTEMPT_COUNT_COLUMN > 0
        ORDER BY $CREATED_AT_COLUMN
        LIMIT 1
        """.trimIndent()
    }

    /**
     * Marks an event as completed with foreach output and triggers the next event.
     *
     * @param listenerId Listener ID (part of composite key)
     * @param eventId CloudEvent ID (part of composite key)
     * @param output Output from foreach.do iteration
     * @return Number of rows updated (1 if successful)
     */
    suspend fun markCompletedWithOutput(
        listenerId: IDV7,
        eventId: String,
        output: String?,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = nowTimestamp()

        // First, mark current event as completed
        val updateSql = """
            UPDATE $tableName
            SET $FOREACH_COMPLETED_COLUMN = TRUE,
                $FOREACH_OUTPUT_COLUMN = ?,
                $OUTBOX_COMPLETED_AT_COLUMN = ?,
                $UPDATED_AT_COLUMN = ?
            WHERE $LISTENER_ID_COLUMN = ? AND $EVENT_ID_COLUMN = ?
        """.trimIndent()

        val updated = conn.prepareStatement(updateSql).use { stmt ->
            stmt.setString(1, output)
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
            setIDV7(stmt, 4, listenerId)
            stmt.setString(5, eventId)
            stmt.executeUpdate()
        }

        updated
    }

    /**
     * Gets completed event data for until expression evaluation.
     * Returns event JSON ordered by created_at (arrival order).
     */
    suspend fun getCompletedEvents(
        listenerId: IDV7,
        connection: Connection? = null
    ): List<String> = withConnection(connection) { conn ->
        conn.prepareStatement(getCompletedEventsSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.getString(EVENT_COLUMN)?.let { add(it) }
                    }
                }
            }
        }
    }

    private val getCompletedEventsSql by lazy {
        """
        SELECT $EVENT_COLUMN FROM $tableName
        WHERE $LISTENER_ID_COLUMN = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
        ORDER BY $CREATED_AT_COLUMN
        """.trimIndent()
    }

    // ========================================
    // Query Methods
    // ========================================

    /**
     * Finds all events for a listener, ordered by created_at (arrival order).
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
        "SELECT * FROM $tableName WHERE $LISTENER_ID_COLUMN = ? ORDER BY $CREATED_AT_COLUMN"
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

    // ========================================
    // FIFO Queue Processing Methods
    // ========================================

    /**
     * Marks listener events as ready for foreach processing.
     *
     * This method finds the head (oldest by sort_key) pending event for each listener
     * that has pending events but no event currently being processed, and marks it
     * as ready by setting outbox_delayed_until = NOW.
     *
     * FIFO ordering is enforced using the sort_key column (auto-increment).
     *
     * Pending events: foreach_output IS NULL AND outbox_delayed_until IS NULL
     * Processing events: foreach_output IS NULL AND outbox_delayed_until IS NOT NULL
     *
     * @param limit Maximum number of listeners to process
     * @param connection Optional existing connection to reuse
     * @return Number of events marked as ready
     */
    suspend fun markReadyForForeach(
        limit: Int,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val sql = when (databaseManager.dbType) {
            LemlineConfigConstants.DB_TYPE_POSTGRESQL -> markReadyForForeachPostgresql(limit)
            LemlineConfigConstants.DB_TYPE_MYSQL -> markReadyForForeachMySql(limit)
            else -> markReadyForForeachH2(limit)
        }

        conn.prepareStatement(sql).use { stmt ->
            stmt.executeUpdate()
        }
    }

    private fun markReadyForForeachPostgresql(limit: Int): String = """
        WITH locked_listeners AS (
            SELECT l.id AS listener_id
            FROM $LISTENER_TABLE l
            WHERE EXISTS (
                SELECT 1
                FROM $tableName p
                WHERE p.$LISTENER_ID_COLUMN = l.id
                  AND p.$FOREACH_COMPLETED_COLUMN = FALSE
                  AND p.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
            )
            AND NOT EXISTS (
                SELECT 1
                FROM $tableName b
                WHERE b.$LISTENER_ID_COLUMN = l.id
                  AND b.$FOREACH_COMPLETED_COLUMN = FALSE
                  AND b.$OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
            )
            ORDER BY l.id
            FOR UPDATE SKIP LOCKED
            LIMIT $limit
        ),
        heads AS (
            SELECT DISTINCT ON (e.$LISTENER_ID_COLUMN)
                   e.$LISTENER_ID_COLUMN, e.$EVENT_ID_COLUMN, e.$FILTER_INDEX_COLUMN
            FROM $tableName e
            JOIN locked_listeners ll ON ll.listener_id = e.$LISTENER_ID_COLUMN
            WHERE e.$FOREACH_COMPLETED_COLUMN = FALSE
              AND e.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
            ORDER BY e.$LISTENER_ID_COLUMN, e.$SORT_KEY_COLUMN
        )
        UPDATE $tableName t
        SET $OUTBOX_DELAYED_UNTIL_COLUMN = now(),
            $UPDATED_AT_COLUMN = now()
        FROM heads h
        WHERE t.$LISTENER_ID_COLUMN  = h.$LISTENER_ID_COLUMN
          AND t.$EVENT_ID_COLUMN     = h.$EVENT_ID_COLUMN
          AND t.$FILTER_INDEX_COLUMN = h.$FILTER_INDEX_COLUMN
    """.trimIndent()

    private fun markReadyForForeachMySql(limit: Int): String = """
        UPDATE $tableName t
        JOIN (
            SELECT * FROM (
                SELECT e.$LISTENER_ID_COLUMN, e.$EVENT_ID_COLUMN, e.$FILTER_INDEX_COLUMN
                FROM $tableName e
                JOIN (
                    -- lock listeners (listener-level SKIP LOCKED)
                    SELECT l.id AS listener_id
                    FROM $LISTENER_TABLE l
                    WHERE EXISTS (
                        SELECT 1
                        FROM $tableName p
                        WHERE p.$LISTENER_ID_COLUMN = l.id
                          AND p.$FOREACH_COMPLETED_COLUMN = FALSE
                          AND p.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM $tableName b
                        WHERE b.$LISTENER_ID_COLUMN = l.id
                          AND b.$FOREACH_COMPLETED_COLUMN = FALSE
                          AND b.$OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
                    )
                    ORDER BY l.id
                    LIMIT $limit
                    FOR UPDATE SKIP LOCKED
                ) ll ON ll.listener_id = e.$LISTENER_ID_COLUMN
                JOIN (
                    -- head per listener: MIN(sort_key) among pending
                    SELECT e2.$LISTENER_ID_COLUMN AS lid, MIN(e2.$SORT_KEY_COLUMN) AS min_sort_key
                    FROM $tableName e2
                    WHERE e2.$FOREACH_COMPLETED_COLUMN = FALSE
                      AND e2.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                    GROUP BY e2.$LISTENER_ID_COLUMN
                ) m ON m.lid = e.$LISTENER_ID_COLUMN AND m.min_sort_key = e.$SORT_KEY_COLUMN
                WHERE e.$FOREACH_COMPLETED_COLUMN = FALSE
                  AND e.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
            ) derived_heads
        ) h
        ON t.$LISTENER_ID_COLUMN  = h.$LISTENER_ID_COLUMN
        AND t.$EVENT_ID_COLUMN     = h.$EVENT_ID_COLUMN
        AND t.$FILTER_INDEX_COLUMN = h.$FILTER_INDEX_COLUMN
        SET t.$OUTBOX_DELAYED_UNTIL_COLUMN = NOW(6),
            t.$UPDATED_AT_COLUMN = NOW(6)
    """.trimIndent()

    private fun markReadyForForeachH2(limit: Int): String = """
        UPDATE $tableName
        SET $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
            $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
        WHERE ($LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FILTER_INDEX_COLUMN) IN (
            SELECT e.$LISTENER_ID_COLUMN, e.$EVENT_ID_COLUMN, e.$FILTER_INDEX_COLUMN
            FROM $tableName e
            JOIN (
                -- Find listeners with pending events but no event being processed
                SELECT l.id AS listener_id
                FROM $LISTENER_TABLE l
                WHERE EXISTS (
                    SELECT 1
                    FROM $tableName p
                    WHERE p.$LISTENER_ID_COLUMN = l.id
                      AND p.$FOREACH_COMPLETED_COLUMN = FALSE
                      AND p.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM $tableName b
                    WHERE b.$LISTENER_ID_COLUMN = l.id
                      AND b.$FOREACH_COMPLETED_COLUMN = FALSE
                      AND b.$OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
                )
                ORDER BY l.id
                LIMIT $limit
            ) ll ON ll.listener_id = e.$LISTENER_ID_COLUMN
            JOIN (
                -- head per listener: MIN(sort_key) among pending
                SELECT e2.$LISTENER_ID_COLUMN, MIN(e2.$SORT_KEY_COLUMN) AS min_sort_key
                FROM $tableName e2
                WHERE e2.$FOREACH_COMPLETED_COLUMN = FALSE
                  AND e2.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                GROUP BY e2.$LISTENER_ID_COLUMN
            ) m ON m.$LISTENER_ID_COLUMN = e.$LISTENER_ID_COLUMN AND m.min_sort_key = e.$SORT_KEY_COLUMN
            WHERE e.$FOREACH_COMPLETED_COLUMN = FALSE
              AND e.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
        )
    """.trimIndent()
}
