// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CREATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_DELAYED_UNTIL_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.common.repositories.ops.OutboxRepository
import com.lemline.runner.common.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.nowTimestamp
import com.lemline.runner.common.repositories.ops.outboxColumns
import com.lemline.runner.common.repositories.ops.readCleanupField
import com.lemline.runner.common.repositories.ops.readOutboxFields
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.listeners.ListenerRepository.Companion.CLOSED_AT_COLUMN
import com.lemline.runner.listeners.ListenerRepository.Companion.FILTERS_COUNT_COLUMN
import com.lemline.runner.listeners.ListenerRepository.Companion.HAS_FOREACH_COLUMN
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val LISTENER_EVENTS_TABLE = "lemline_listener_events"

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
class ListenerEventRepository : CrudRepository<ListenerEventModel>(),
    WithOutboxRepository<ListenerEventModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    // Uses specialized ListenerEventOutboxRepository for FIFO-aware processing
    private val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig) }

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
    }

    override val tableName = LISTENER_EVENTS_TABLE

    override val columns: ColumnBindings<ListenerEventModel> by lazy {
        ColumnBindingsBuilder<ListenerEventModel>().apply {
            outboxColumns()
            cleanupColumns()

            key(LISTENER_ID_COLUMN) { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenerId) }
            key(EVENT_ID_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.eventId) }
            nullableKey(FILTER_INDEX_COLUMN) { stmt, entity, idx ->
                when (val filterIndex = entity.filterIndex) {
                    null -> stmt.setNull(idx, java.sql.Types.INTEGER)
                    else -> stmt.setInt(idx, filterIndex)
                }
            }

            // Core columns
            column(EVENT_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.event) }
            column(SORT_KEY_COLUMN) { stmt, entity, idx -> stmt.setInt(idx, entity.sortKey) }
            column(FOREACH_COMPLETED_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.foreachCompleted)
            }
            column(FOREACH_OUTPUT_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.foreachOutput)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet): ListenerEventModel = ListenerEventModel(
        listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!,
        eventId = rs.getString(EVENT_ID_COLUMN),
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN).takeUnless { rs.wasNull() },
        event = rs.getString(EVENT_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
    ).apply {
        sortKey = rs.getInt(SORT_KEY_COLUMN)
        foreachCompleted = rs.getBoolean(FOREACH_COMPLETED_COLUMN)
        foreachOutput = rs.getString(FOREACH_OUTPUT_COLUMN)
        createdAt = rs.getInstant(CREATED_AT_COLUMN)
    }
        .readOutboxFields(rs)
        .readCleanupField(rs)

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
        // Filter keys to only include ONE or ANY strategy - skip query if none
        val oneAnyKeys = keys.filter {
            it.listenerStrategy == ListenerStrategy.ONE || it.listenerStrategy == ListenerStrategy.ANY
        }
        if (oneAnyKeys.isEmpty()) return 0

        return databaseConfig.withTransaction(connection) { conn ->
            // Insert event, but only the first one per listener due to UNIQUE(listener_id, filter_index)
            // If has_foreach: foreach_completed = false, foreach_output = null, outbox_delayed_until is not set (defaults to NULL) - markReadyForForeach will set it
            // Else: foreach_completed = true, foreach_output = event
            // For ONE/ANY, sort_key is always 0 (only one event per listener ever)
            val columns = """
                $LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FILTER_INDEX_COLUMN, $EVENT_COLUMN,
                $SORT_KEY_COLUMN, $FOREACH_COMPLETED_COLUMN, $FOREACH_OUTPUT_COLUMN, $OUTBOX_SCHEDULED_FOR_COLUMN
            """.trimIndent()

            val selectSql = """
                SELECT
                    l.$ID_COLUMN,                                               /* listener_id */
                    ?,                                                          /* event_id */
                    0,                                                          /* filter_index = 0 for ONE/ANY */
                    ?,                                                          /* event */
                    0,                                                          /* sort_key = 0 (only one event per listener) */
                    NOT l.$HAS_FOREACH_COLUMN,                                  /* foreach_completed = TRUE if no foreach */
                    CASE WHEN NOT l.$HAS_FOREACH_COLUMN THEN ? END,             /* foreach_output = event if not has foreach */
                    CASE WHEN l.$HAS_FOREACH_COLUMN THEN CURRENT_TIMESTAMP END  /* outbox_scheduled_for = now if has foreach */
                FROM $LISTENER_TABLE l
                WHERE l.$CLOSED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(oneAnyKeys, "l")})
            """.trimIndent()

            val insertSelectSql = databaseConfig.insertIgnoreSelect(tableName, columns, selectSql)

            val insertedCount = conn.prepareStatement(insertSelectSql).use { stmt ->
                var paramIdx = 1
                stmt.setString(paramIdx++, eventId)
                stmt.setString(paramIdx++, eventJson)
                stmt.setString(paramIdx++, eventJson) // foreach_output = event for non-foreach
                ListenerQueryKey.bindAllParameters(oneAnyKeys, stmt, paramIdx)

                stmt.executeUpdate()
            }

            // Mark listeners as completed (first event received = stop collecting)
            // For ONE/ANY, receiving any event completes the listener
            if (insertedCount > 0) {
                markOneAnyListenersCompleted(eventId, oneAnyKeys, conn)
            }

            insertedCount
        }
    }

    /**
     * Marks ONE/ANY listeners as completed when they have received an event.
     * Uses the eventId to precisely target only listeners that received this specific event.
     * Called immediately after inserting events in batchInsertForOneAny.
     *
     * Note: Strategy filtering is done in the INSERT (only ONE/ANY listeners get events),
     * so the eventId check is sufficient to target the right listeners.
     */
    private fun markOneAnyListenersCompleted(
        eventId: String,
        keys: List<ListenerQueryKey>,
        conn: Connection
    ): Int {
        val now = nowTimestamp()

        val updateSql = """
            UPDATE $LISTENER_TABLE l
            SET $CLOSED_AT_COLUMN = ?,
                $UPDATED_AT_COLUMN = ?
            WHERE l.$CLOSED_AT_COLUMN IS NULL
              AND EXISTS (
                  SELECT 1 FROM $tableName e
                  WHERE e.$LISTENER_ID_COLUMN = l.$ID_COLUMN
                    AND e.$EVENT_ID_COLUMN = ?
              )
              AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
        """.trimIndent()

        return conn.prepareStatement(updateSql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setTimestamp(2, now)
            stmt.setString(3, eventId)
            ListenerQueryKey.bindAllParameters(keys, stmt, 4)
            stmt.executeUpdate()
        }
    }

    /**
     * Batch inserts events for accumulating strategies (ALL/ANY+until) using INSERT...SELECT.
     *
     * Uses `(listener_id, event_id, filter_index)` as composite PK for natural idempotency.
     *
     * FIFO ordering is enforced using per-listener sort_key (0, 1, 2...). The foreach outbox
     * processor uses MIN(sort_key) to pick the first unprocessed event per listener.
     *
     * Uses FOR UPDATE to lock matching listeners and prevent race conditions when
     * calculating the per-listener sort_key.
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
    suspend fun batchInsertForAllAnyUntil(
        keys: List<ListenerQueryKey>,
        eventId: String,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        // Filter keys to only include accumulating strategies - skip query if none
        val accumulatingStrategies = setOf(
            ListenerStrategy.ALL,
            ListenerStrategy.ANY_UNTIL_EXPR,
            ListenerStrategy.ANY_UNTIL_EVENT
        )
        val accumulatingKeys = keys.filter { it.listenerStrategy in accumulatingStrategies }
        if (accumulatingKeys.isEmpty()) return 0

        // Group by filterIndex first (in-memory, before acquiring locks)
        val byFilterIndex = accumulatingKeys.groupBy { it.filterIndex }

        return databaseConfig.withTransaction(connection) { conn ->
            // Step 1: Lock matching listeners with FOR UPDATE to prevent race conditions
            // when calculating sort_key
            lockMatchingListeners(accumulatingKeys, conn)

            // Step 2: Insert with calculated sort_key
            // Build UNION ALL of all filterIndex queries
            // Include has_foreach for completion logic
            val unionParts = byFilterIndex.map { (filterIndex, queryKeys) ->
                """
                SELECT
                    l.$ID_COLUMN as $LISTENER_ID_COLUMN,
                    $filterIndex as $FILTER_INDEX_COLUMN,
                    l.$HAS_FOREACH_COLUMN
                FROM $LISTENER_TABLE l
                WHERE l.$CLOSED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(queryKeys, "l")})
                """.trimIndent()
            }

            val unionSql = unionParts.joinToString("\nUNION ALL\n")

            // Single INSERT...SELECT with calculated sort_key
            // sort_key = COALESCE(max existing sort_key, -1) + ROW_NUMBER within batch
            // Note: outbox_delayed_until is not set (defaults to NULL) - markReadyForForeach will set it
            // Note: created_at uses database default (CURRENT_TIMESTAMP)
            val columns = """
                $LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FILTER_INDEX_COLUMN, $EVENT_COLUMN,
                $SORT_KEY_COLUMN, $FOREACH_COMPLETED_COLUMN, $FOREACH_OUTPUT_COLUMN, $OUTBOX_SCHEDULED_FOR_COLUMN
            """.trimIndent()

            val selectSql = buildInsertSelectWithSortKey(unionSql)

            val insertSelectSql = databaseConfig.insertIgnoreSelect(tableName, columns, selectSql)

            val insertedCount = conn.prepareStatement(insertSelectSql).use { stmt ->
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

            // Mark ALL strategy listeners as completed when all filters have events
            if (insertedCount > 0) {
                markAllListenersCompleted(eventId, keys, conn)
            }

            insertedCount
        }
    }

    /**
     * Locks matching listeners with FOR UPDATE to prevent race conditions
     * when calculating sort_key for new events.
     */
    private fun lockMatchingListeners(keys: List<ListenerQueryKey>, conn: Connection) {
        val lockSql = """
            SELECT l.$ID_COLUMN
            FROM $LISTENER_TABLE l
            WHERE l.$CLOSED_AT_COLUMN IS NULL
              AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
            FOR UPDATE
        """.trimIndent()

        conn.prepareStatement(lockSql).use { stmt ->
            ListenerQueryKey.bindAllParameters(keys, stmt, 1)
            stmt.executeQuery() // Execute to lock, results not needed
        }
    }

    /**
     * Builds the SELECT statement for insert with calculated sort_key.
     * sort_key = COALESCE(max existing sort_key for listener, -1) + ROW_NUMBER within batch
     *
     * Uses LEFT JOIN to get the current max sort_key per listener, then adds
     * ROW_NUMBER() OVER (PARTITION BY listener_id) to assign sequential values
     * for multiple rows being inserted for the same listener in one batch.
     */
    private fun buildInsertSelectWithSortKey(unionSql: String): String {
        // ROW_NUMBER ordering: filter_index ASC with NULLS LAST (ANY_UNTIL_* has NULL filter_index)
        val rowNumberOrderBy = when (databaseConfig.dbType) {
            DatabaseType.MYSQL -> "COALESCE(m.$FILTER_INDEX_COLUMN, 2147483647)"
            else -> "m.$FILTER_INDEX_COLUMN NULLS LAST"
        }

        return """
            SELECT
                m.$LISTENER_ID_COLUMN,                                      /* listener_id */
                ?,                                                          /* event_id */
                m.$FILTER_INDEX_COLUMN,                                     /* filter_index */
                ?,                                                          /* event */
                COALESCE(existing.max_sort_key, -1) + ROW_NUMBER() OVER (
                    PARTITION BY m.$LISTENER_ID_COLUMN
                    ORDER BY $rowNumberOrderBy
                ),                                                          /* sort_key */
                NOT m.$HAS_FOREACH_COLUMN,                                  /* foreach_completed = TRUE if no foreach */
                CASE WHEN NOT m.$HAS_FOREACH_COLUMN THEN ? END,             /* foreach_output = event for non-foreach */
                CASE WHEN m.$HAS_FOREACH_COLUMN THEN CURRENT_TIMESTAMP END  /* outbox_scheduled_for = NULL if not foreach */
            FROM (
                $unionSql
            ) m
            LEFT JOIN (
                SELECT $LISTENER_ID_COLUMN, MAX($SORT_KEY_COLUMN) as max_sort_key
                FROM $tableName
                GROUP BY $LISTENER_ID_COLUMN
            ) existing ON existing.$LISTENER_ID_COLUMN = m.$LISTENER_ID_COLUMN
        """.trimIndent()
    }

    /**
     * Marks ALL strategy listeners as completed when all filters have at least one event.
     * Uses the eventId to precisely target only listeners that received this specific event.
     * Called immediately after inserting events in batchInsertForAccumulating.
     *
     * Note: Only targets ALL strategy. ANY_UNTIL_* strategies have separate completion logic
     * (batchMarkReadyByUntilEvent for ANY_UNTIL_EVENT, markListenerCompleted for ANY_UNTIL_EXPR).
     */
    private fun markAllListenersCompleted(
        eventId: String,
        keys: List<ListenerQueryKey>,
        conn: Connection
    ): Int {
        // Filter keys to only include ALL strategy - skip query if none
        val allStrategyKeys = keys.filter { it.listenerStrategy == ListenerStrategy.ALL }
        if (allStrategyKeys.isEmpty()) return 0

        val now = nowTimestamp()

        val updateSql = """
            UPDATE $LISTENER_TABLE l
            SET $CLOSED_AT_COLUMN = ?,
                $UPDATED_AT_COLUMN = ?
            WHERE l.$CLOSED_AT_COLUMN IS NULL
              AND l.$FILTERS_COUNT_COLUMN IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM $tableName e
                  WHERE e.$LISTENER_ID_COLUMN = l.$ID_COLUMN
                    AND e.$EVENT_ID_COLUMN = ?
              )
              AND (
                  SELECT COUNT(DISTINCT e.$FILTER_INDEX_COLUMN)
                  FROM $tableName e
                  WHERE e.$LISTENER_ID_COLUMN = l.$ID_COLUMN
              ) >= l.$FILTERS_COUNT_COLUMN
              AND (${ListenerQueryKey.buildWhereClause(allStrategyKeys, "l")})
        """.trimIndent()

        return conn.prepareStatement(updateSql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setTimestamp(2, now)
            stmt.setString(3, eventId)
            ListenerQueryKey.bindAllParameters(allStrategyKeys, stmt, 4)
            stmt.executeUpdate()
        }
    }

    // ========================================
    // Foreach Processing Methods
    // ========================================

    /**
     * Marks a specific foreach event as completed with output.
     *
     * Used after foreach.do completes for a CloudEvent.
     * Sets foreach_completed = TRUE so next event can be processed.
     *
     * @param listenerId Listener ID (from nodeStack)
     * @param sortKey Sort key identifying the event (from ForeachState.index)
     * @param output Output from foreach.do iteration
     * @return Number of rows updated (should be 1)
     */
    suspend fun markForeachCompleted(
        listenerId: IDV7,
        sortKey: Int,
        output: String?,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
        val now = nowTimestamp()

        conn.prepareStatement(markForeachCompletedSql).use { stmt ->
            stmt.setString(1, output)
            stmt.setTimestamp(2, now)
            setIDV7(stmt, 3, listenerId)
            stmt.setInt(4, sortKey)
            stmt.executeUpdate()
        }
    }

    private val markForeachCompletedSql by lazy {
        """
        UPDATE $tableName
        SET $FOREACH_COMPLETED_COLUMN = TRUE,
            $FOREACH_OUTPUT_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $LISTENER_ID_COLUMN = ?
          AND $SORT_KEY_COLUMN = ?
          AND $FOREACH_COMPLETED_COLUMN = FALSE
        """.trimIndent()
    }

    /**
     * Batch-loads completed outputs for multiple listeners in a single query.
     *
     * Returns a map of listener ID to list of completed outputs (foreach_output for foreach listeners,
     * event JSON for non-foreach listeners). Outputs are ordered by sort_key for deterministic ordering.
     *
     * This method is optimized for outbox completion processing to avoid N+1 queries.
     *
     * @param listenerIds List of listener IDs to fetch outputs for
     * @param connection Optional existing connection to reuse
     * @return Map of listener ID to list of output strings (empty list if no events)
     */
    suspend fun findCompletedOutputsByListeners(
        listenerIds: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, List<String>> {
        if (listenerIds.isEmpty()) return emptyMap()

        return databaseConfig.withConnection(connection) { conn ->
            val placeholders = listenerIds.joinToString(",") { "?" }

            val sql = """
                SELECT $LISTENER_ID_COLUMN, $EVENT_ID_COLUMN, $FOREACH_OUTPUT_COLUMN
                FROM $tableName
                WHERE $LISTENER_ID_COLUMN IN ($placeholders)
                  AND $FOREACH_OUTPUT_COLUMN IS NOT NULL
                ORDER BY $LISTENER_ID_COLUMN, $SORT_KEY_COLUMN
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                listenerIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }

                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<IDV7, MutableList<String>>()
                    val seenEvents = mutableSetOf<Pair<IDV7, String>>()
                    while (rs.next()) {
                        val listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!
                        val eventId = rs.getString(EVENT_ID_COLUMN)
                        val output = rs.getString(FOREACH_OUTPUT_COLUMN)
                        val key = listenerId to eventId
                        if (seenEvents.add(key)) result.getOrPut(listenerId) { mutableListOf() }.add(output)
                    }
                    result
                }
            }
        }
    }

    // ========================================
    // Query Methods
    // ========================================

    /**
     * Finds all events for a listener, ordered by created_at (arrival order).
     *
     * @param listenerId The listener ID to query events for
     * @param connection Optional existing connection to reuse
     * @return List of events ordered by arrival time, empty list if none found
     */
    suspend fun findByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): List<ListenerEventModel> = databaseConfig.withConnection(connection) { conn ->
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
     *
     * @param listenerId The listener ID to count events for
     * @param connection Optional existing connection to reuse
     * @return Number of events for the listener
     */
    suspend fun countByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
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
     * ## FIFO Ordering
     *
     * FIFO ordering is enforced using the `sort_key` column (auto-increment).
     * - **Pending events**: `foreach_completed = FALSE AND outbox_delayed_until IS NULL`
     * - **Processing events**: `foreach_completed = FALSE AND outbox_delayed_until IS NOT NULL`
     *
     * ## Database-Specific Implementation
     *
     * Each database uses different syntax for the same logical operation:
     * - PostgreSQL: CTEs with `DISTINCT ON` for head selection
     * - MySQL: `UPDATE...JOIN` with derived tables and `MIN(sort_key)`
     * - H2: `UPDATE...WHERE EXISTS` with correlated subqueries
     *
     * @param limit Maximum number of listeners to process
     * @param connection Optional existing connection to reuse
     * @return Number of events marked as ready
     */
    suspend fun markReadyForForeach(
        limit: Int,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
        val sql = buildMarkReadyForForeachSql(limit)
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeUpdate()
        }
    }

    /**
     * Builds the database-specific SQL for marking events ready for foreach processing.
     * Extracted for testability and to centralize the database branching.
     */
    private fun buildMarkReadyForForeachSql(limit: Int): String = when (databaseConfig.dbType) {
        DatabaseType.POSTGRESQL -> buildMarkReadyPostgresql(limit)
        DatabaseType.MYSQL -> buildMarkReadyMySql(limit)
        DatabaseType.H2 -> buildMarkReadyH2(limit)
    }

    // ----------------------------------------
    // Common SQL Fragments
    // ----------------------------------------

    /** Condition for pending (not yet processing) events */
    private val pendingEventCondition = "$FOREACH_COMPLETED_COLUMN = FALSE AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL"

    /** Condition for events currently being processed */
    private val processingEventCondition =
        "$FOREACH_COMPLETED_COLUMN = FALSE AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL"

    /** NULL-safe composite key match for (listener_id, event_id, filter_index) */
    private val compositeKeyMatch = """
        t.$LISTENER_ID_COLUMN = h.$LISTENER_ID_COLUMN
        AND t.$EVENT_ID_COLUMN = h.$EVENT_ID_COLUMN
        AND (t.$FILTER_INDEX_COLUMN = h.$FILTER_INDEX_COLUMN
             OR (t.$FILTER_INDEX_COLUMN IS NULL AND h.$FILTER_INDEX_COLUMN IS NULL))
    """.trimIndent()

    /**
     * Builds the subquery to find listeners eligible for FIFO processing.
     * A listener is eligible if it has pending events but no event currently being processed.
     *
     * Note: PostgreSQL requires LIMIT after FOR UPDATE, while MySQL/H2 require it before.
     */
    private fun buildEligibleListenersSubquery(limit: Int): String {
        val limitAndLock = when (databaseConfig.dbType) {
            DatabaseType.POSTGRESQL -> "FOR UPDATE SKIP LOCKED\n        LIMIT $limit"
            else -> "LIMIT $limit\n        FOR UPDATE SKIP LOCKED"
        }
        return """
            SELECT l.$ID_COLUMN AS listener_id
            FROM $LISTENER_TABLE l
            WHERE EXISTS (
                SELECT 1 FROM $tableName p
                WHERE p.$LISTENER_ID_COLUMN = l.$ID_COLUMN AND p.$pendingEventCondition
            )
            AND NOT EXISTS (
                SELECT 1 FROM $tableName b
                WHERE b.$LISTENER_ID_COLUMN = l.$ID_COLUMN AND b.$processingEventCondition
            )
            ORDER BY l.$ID_COLUMN
            $limitAndLock
        """.trimIndent()
    }

    /**
     * Builds the subquery to find the head (oldest by sort_key) pending event per listener.
     * Used by MySQL and H2 which don't support PostgreSQL's DISTINCT ON.
     *
     * Note: MySQL aliases the column as 'lid', H2 keeps it as-is for simpler join syntax.
     */
    private fun buildHeadPerListenerSubquery(): String {
        val lidColumn = when (databaseConfig.dbType) {
            DatabaseType.MYSQL -> "$LISTENER_ID_COLUMN AS lid"
            else -> LISTENER_ID_COLUMN
        }
        return """
            SELECT e2.$lidColumn, MIN(e2.$SORT_KEY_COLUMN) AS min_sort_key
            FROM $tableName e2
            WHERE e2.$pendingEventCondition
            GROUP BY e2.$LISTENER_ID_COLUMN
        """.trimIndent()
    }

    // ----------------------------------------
    // Database-Specific SQL Builders
    // ----------------------------------------

    private fun buildMarkReadyPostgresql(limit: Int): String = """
        WITH locked_listeners AS (
            ${buildEligibleListenersSubquery(limit)}
        ),
        heads AS (
            SELECT DISTINCT ON (e.$LISTENER_ID_COLUMN)
                   e.$LISTENER_ID_COLUMN, e.$EVENT_ID_COLUMN, e.$FILTER_INDEX_COLUMN
            FROM $tableName e
            JOIN locked_listeners ll ON ll.listener_id = e.$LISTENER_ID_COLUMN
            WHERE e.$pendingEventCondition
            ORDER BY e.$LISTENER_ID_COLUMN, e.$SORT_KEY_COLUMN
        )
        UPDATE $tableName t
        SET $OUTBOX_SCHEDULED_FOR_COLUMN = now(),
            $OUTBOX_DELAYED_UNTIL_COLUMN = now(),
            $UPDATED_AT_COLUMN = now()
        FROM heads h
        WHERE $compositeKeyMatch
    """.trimIndent()

    private fun buildMarkReadyMySql(limit: Int): String = """
        UPDATE $tableName t
        JOIN (
            SELECT * FROM (
                SELECT e.$LISTENER_ID_COLUMN, e.$EVENT_ID_COLUMN, e.$FILTER_INDEX_COLUMN
                FROM $tableName e
                JOIN (${buildEligibleListenersSubquery(limit)}) ll
                    ON ll.listener_id = e.$LISTENER_ID_COLUMN
                JOIN (${buildHeadPerListenerSubquery()}) m
                    ON m.lid = e.$LISTENER_ID_COLUMN AND m.min_sort_key = e.$SORT_KEY_COLUMN
                WHERE e.$pendingEventCondition
            ) derived_heads
        ) h
        ON $compositeKeyMatch
        SET t.$OUTBOX_SCHEDULED_FOR_COLUMN = NOW(6),
            t.$OUTBOX_DELAYED_UNTIL_COLUMN = NOW(6),
            t.$UPDATED_AT_COLUMN = NOW(6)
    """.trimIndent()

    private fun buildMarkReadyH2(limit: Int): String = """
        UPDATE $tableName AS t
        SET $OUTBOX_SCHEDULED_FOR_COLUMN = CURRENT_TIMESTAMP,
            $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
            $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
        WHERE EXISTS (
            SELECT 1
            FROM $tableName e
            JOIN (${buildEligibleListenersSubquery(limit)}) ll
                ON ll.listener_id = e.$LISTENER_ID_COLUMN
            JOIN (${buildHeadPerListenerSubquery()}) m
                ON m.$LISTENER_ID_COLUMN = e.$LISTENER_ID_COLUMN AND m.min_sort_key = e.$SORT_KEY_COLUMN
            WHERE e.$LISTENER_ID_COLUMN = t.$LISTENER_ID_COLUMN
              AND e.$EVENT_ID_COLUMN = t.$EVENT_ID_COLUMN
              AND (e.$FILTER_INDEX_COLUMN = t.$FILTER_INDEX_COLUMN
                   OR (e.$FILTER_INDEX_COLUMN IS NULL AND t.$FILTER_INDEX_COLUMN IS NULL))
              AND e.$pendingEventCondition
        )
    """.trimIndent()
}
