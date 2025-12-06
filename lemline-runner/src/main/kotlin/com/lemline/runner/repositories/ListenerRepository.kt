// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ListenerModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

const val LISTENER_TABLE = "lemline_listeners"

/**
 * Key for batch querying listeners by workflow identity and correlation.
 */
data class ListenerQueryKey(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val correlationValuesJson: String?
)

/**
 * Repository for managing listener instances in the outbox pattern.
 *
 * This repository handles:
 * - CRUD operations for listeners
 * - Finding matching listeners for CloudEvent routing
 * - Atomic updates for race-safe event handling
 * - Timeout detection
 *
 * ## Event Storage
 *
 * - **ONE/ANY (without until)**: Single event stored in `event` column
 * - **ALL/ANY+until**: Events accumulated in `lemline_listener_events` table
 *
 * @see ListenerModel for the entity model
 * @see ListenerEventRepository for accumulated events
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerRepository : OutboxRepository<ListenerModel>() {

    companion object Companion {
        const val TIMEOUT_AT_COLUMN = "timeout_at"
        const val CORRELATION_VALUES_COLUMN = "correlation_values"
        const val EVENT_COLUMN = "event"
        const val TOTAL_FILTERS_COLUMN = "total_filters"
        const val UPDATED_AT_COLUMN = "updated_at"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, ListenerModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            // Override WORKFLOW_STATE_COLUMN to store the full InstanceMessage (includes workflowInfo)
            WORKFLOW_STATE_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage.toJsonString())
            },
            // Override workflow info columns to use ListenerModel's derived fields
            WORKFLOW_NAMESPACE_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.workflowNamespace.toString())
            },
            WORKFLOW_NAME_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.workflowName.toString())
            },
            WORKFLOW_VERSION_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.workflowVersion.toString())
            },
            TIMEOUT_AT_COLUMN to { stmt, entity, idx ->
                entity.timeoutAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            },
            CORRELATION_VALUES_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.correlationValues)
            },
            EVENT_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.event)
            },
            TOTAL_FILTERS_COLUMN to { stmt, entity, idx ->
                entity.totalFilters?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
        )
    }

    override fun createModel(rs: ResultSet): ListenerModel = ListenerModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        // InstanceMessage contains all workflow info (namespace, name, version, id, position)
        instanceMessage = InstanceMessage.fromJsonString(rs.getString(WORKFLOW_STATE_COLUMN)),
        timeoutAt = rs.getInstant(TIMEOUT_AT_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    ).apply {
        correlationValues = rs.getString(CORRELATION_VALUES_COLUMN)
        event = rs.getString(EVENT_COLUMN)
        totalFilters = rs.getInt(TOTAL_FILTERS_COLUMN).takeIf { !rs.wasNull() }
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN)
        outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN)
    }

    /**
     * Batch finds active listeners for multiple query keys in a single database round-trip.
     *
     * This method builds a dynamic query with OR conditions to fetch listeners matching
     * any of the provided keys. Each key specifies workflow identity, position, and
     * optional correlation values.
     *
     * @param keys List of query keys to match
     * @param connection Optional database connection
     * @return List of matching active listeners (caller must match back to keys)
     */
    suspend fun findByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): List<ListenerModel> {
        if (keys.isEmpty()) return emptyList()

        return withConnection(connection) { conn ->
            // Build dynamic SQL with OR conditions for each key
            val conditions = keys.map { key ->
                if (key.correlationValuesJson == null) {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ?)"
                } else {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ? AND ($CORRELATION_VALUES_COLUMN IS NULL OR $CORRELATION_VALUES_COLUMN = ?))"
                }
            }

            val sql = """
                SELECT * FROM $tableName
                WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var paramIndex = 1
                for (key in keys) {
                    stmt.setString(paramIndex++, key.workflowInfo.workflowNamespace.toString())
                    stmt.setString(paramIndex++, key.workflowInfo.workflowName.toString())
                    stmt.setString(paramIndex++, key.workflowInfo.workflowVersion.toString())
                    stmt.setString(paramIndex++, key.position.toString())
                    if (key.correlationValuesJson != null) {
                        stmt.setString(paramIndex++, key.correlationValuesJson)
                    }
                }
                stmt.executeQuery().use { it.toModels() }
            }
        }
    }

    /**
     * Finds listeners that have timed out.
     *
     * @param limit Maximum number of listeners to return
     * @param connection Optional database connection
     * @return List of timed out listeners
     */
    suspend fun findTimedOut(limit: Int, connection: Connection? = null): List<ListenerModel> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findTimedOutSql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findTimedOutSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
          AND $TIMEOUT_AT_COLUMN IS NOT NULL
          AND $TIMEOUT_AT_COLUMN <= ?
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    /**
     * Marks a listener as completed.
     *
     * @param id Listener ID
     * @param connection Optional database connection
     * @return Number of rows updated
     */
    suspend fun markCompleted(id: IDV7, connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(markCompletedSql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
                stmt.setTimestamp(2, Timestamp.from(Clock.System.now().toJavaInstant()))
                setIDV7(stmt, 3, id)
                stmt.executeUpdate()
            }
        }

    private val markCompletedSql by lazy {
        """
        UPDATE $tableName
        SET $OUTBOX_COMPLETED_AT_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Marks a listener as failed.
     *
     * @param id Listener ID
     * @param errorClass Exception class name
     * @param errorMessage Error message
     * @param errorStackTrace Stack trace
     * @param connection Optional database connection
     * @return Number of rows updated
     */
    suspend fun markFailed(
        id: IDV7,
        errorClass: String?,
        errorMessage: String?,
        errorStackTrace: String?,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(markFailedSql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
            stmt.setString(2, errorClass)
            stmt.setString(3, errorMessage)
            stmt.setString(4, errorStackTrace)
            stmt.setTimestamp(5, Timestamp.from(Clock.System.now().toJavaInstant()))
            setIDV7(stmt, 6, id)
            stmt.executeUpdate()
        }
    }

    private val markFailedSql by lazy {
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
     * Deletes all listeners for a given workflow definition.
     * Used when a workflow definition is deleted and all its listeners should be removed.
     *
     * @param namespace Workflow namespace
     * @param name Workflow name
     * @param version Workflow version
     * @param connection Optional database connection
     * @return Number of listeners deleted
     */
    suspend fun deleteByWorkflowDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByWorkflowDefinitionSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.setString(3, version.toString())
            stmt.executeUpdate()
        }
    }

    private val deleteByWorkflowDefinitionSql by lazy {
        """
        DELETE FROM $tableName
        WHERE $WORKFLOW_NAMESPACE_COLUMN = ?
          AND $WORKFLOW_NAME_COLUMN = ?
          AND $WORKFLOW_VERSION_COLUMN = ?
        """.trimIndent()
    }

    // ========================================
    // Atomic Update Methods for Race-Safe CloudEvent Processing
    // ========================================

    /**
     * Atomically marks a listener as ready for completion (ONE/ANY without until).
     *
     * This method uses a single atomic UPDATE with WHERE guards to prevent race conditions:
     * - `outbox_delayed_until IS NULL` → still waiting (not yet completing)
     * - `outbox_completed_at IS NULL` → not yet completed
     * - `outbox_failed_at IS NULL` → not failed
     *
     * If the update succeeds (rows affected > 0), the listener is now ready for completion
     * and will be picked up by ListenerCompletionOutbox.
     *
     * @param id Listener ID
     * @param event JSON string of the event to store
     * @param connection Optional database connection
     * @return Number of rows updated (0 = already completing/completed, 1 = success)
     */
    suspend fun tryMarkReadyForCompletion(
        id: IDV7,
        event: String,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = Timestamp.from(Clock.System.now().toJavaInstant())

        conn.prepareStatement(markReadyForCompletionSql).use { stmt ->
            stmt.setString(1, event)
            stmt.setTimestamp(2, now) // outbox_delayed_until = NOW()
            stmt.setTimestamp(3, now) // updated_at
            setIDV7(stmt, 4, id)
            stmt.executeUpdate()
        }
    }

    private val markReadyForCompletionSql by lazy {
        """
        UPDATE $tableName
        SET $EVENT_COLUMN = ?,
            $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
          AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        """.trimIndent()
    }

    /**
     * Marks a listener as ready for completion (for ALL/ANY+until after events table is populated).
     *
     * This is called after events have been inserted into lemline_listener_events
     * and the completion condition is met.
     *
     * @param id Listener ID
     * @param connection Optional database connection
     * @return Number of rows updated (0 = already completing/completed, 1 = success)
     */
    suspend fun markReadyForCompletionFromEvents(
        id: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = Timestamp.from(Clock.System.now().toJavaInstant())

        conn.prepareStatement(markReadyForCompletionFromEventsSql).use { stmt ->
            stmt.setTimestamp(1, now) // outbox_delayed_until = NOW()
            stmt.setTimestamp(2, now) // updated_at
            setIDV7(stmt, 3, id)
            stmt.executeUpdate()
        }
    }

    private val markReadyForCompletionFromEventsSql by lazy {
        """
        UPDATE $tableName
        SET $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
          AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        """.trimIndent()
    }

    /**
     * Marks listeners as ready for completion by query keys (ONE/ANY strategy).
     *
     * This method performs a direct UPDATE without first SELECT, avoiding loading
     * potentially millions of listeners into memory. It updates all active listeners
     * matching the provided keys in a single database operation.
     *
     * @param keys List of query keys identifying listeners to update
     * @param event JSON string of the event to store (same for all matched listeners)
     * @param connection Optional database connection
     * @return Number of listeners that were successfully marked for completion
     */
    suspend fun markReadyForCompletionByKeys(
        keys: List<ListenerQueryKey>,
        event: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = Timestamp.from(Clock.System.now().toJavaInstant())

            // Build WHERE conditions from keys
            val conditions = keys.map { key ->
                if (key.correlationValuesJson == null) {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ?)"
                } else {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ? AND ($CORRELATION_VALUES_COLUMN IS NULL OR $CORRELATION_VALUES_COLUMN = ?))"
                }
            }

            val sql = """
                UPDATE $tableName
                SET $EVENT_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, event)
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                for (key in keys) {
                    stmt.setString(idx++, key.workflowInfo.workflowNamespace.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowName.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowVersion.toString())
                    stmt.setString(idx++, key.position.toString())
                    if (key.correlationValuesJson != null) {
                        stmt.setString(idx++, key.correlationValuesJson)
                    }
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Batch marks listeners as ready for completion (for ALL/ANY+until).
     * Stores the aggregated events JSON in each listener's event column.
     *
     * Uses CASE WHEN to set different event values per listener in a single UPDATE.
     *
     * @param listenerEvents Map of listener ID to aggregated events JSON
     * @param connection Optional database connection
     * @return Number of listeners that were successfully marked for completion
     */
    suspend fun batchMarkReadyForCompletionFromEvents(
        listenerEvents: Map<IDV7, String>,
        connection: Connection? = null
    ): Int {
        if (listenerEvents.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = Timestamp.from(Clock.System.now().toJavaInstant())
            val ids = listenerEvents.keys.toList()
            val placeholders = ids.joinToString(", ") { "?" }

            // Build CASE WHEN clause for event values
            val caseWhen = ids.joinToString(" ") { "WHEN ? THEN ?" }

            val sql = """
                UPDATE $tableName
                SET $EVENT_COLUMN = CASE $ID_COLUMN $caseWhen END,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $ID_COLUMN IN ($placeholders)
                  AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var paramIndex = 1

                // CASE WHEN parameters: (id, event) pairs
                for ((id, event) in listenerEvents) {
                    setIDV7(stmt, paramIndex++, id)
                    stmt.setString(paramIndex++, event)
                }

                // SET clause parameters
                stmt.setTimestamp(paramIndex++, now)
                stmt.setTimestamp(paramIndex++, now)

                // WHERE IN clause parameters
                for (id in ids) {
                    setIDV7(stmt, paramIndex++, id)
                }

                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks listeners as terminated by query keys (ANY + until(event) strategy).
     *
     * This method performs a direct UPDATE with a subquery to aggregate accumulated events,
     * avoiding loading potentially millions of listeners into memory. It updates all active
     * listeners matching the provided keys in a single database operation.
     *
     * The accumulated events from lemline_listener_events are aggregated into a JSON array
     * and stored in the listener's event column.
     *
     * @param keys List of query keys identifying listeners to terminate
     * @param connection Optional database connection
     * @return Number of listeners that were successfully marked for completion
     */
    suspend fun markTerminatedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = Timestamp.from(Clock.System.now().toJavaInstant())

            // Build WHERE conditions from keys (termination doesn't use correlation)
            val conditions = keys.map {
                "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ?)"
            }

            // Use database-specific JSON aggregation function
            val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

            val sql = """
                UPDATE $tableName l
                SET $EVENT_COLUMN = (
                    SELECT $jsonAgg
                    FROM $LISTENER_EVENT_TABLE e
                    WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                ),
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                for (key in keys) {
                    stmt.setString(idx++, key.workflowInfo.workflowNamespace.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowName.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowVersion.toString())
                    stmt.setString(idx++, key.position.toString())
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks listeners as completed by query keys (ALL strategy).
     *
     * This method performs a direct UPDATE with subquery to:
     * 1. Aggregate accumulated events from lemline_listener_events into JSON array
     * 2. Only update listeners where event count >= total_filters
     *
     * This avoids loading potentially millions of listeners into memory.
     *
     * @param keys List of query keys identifying listeners to check for completion
     * @param connection Optional database connection
     * @return Number of listeners that were successfully marked for completion
     */
    suspend fun markAllCompletedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = Timestamp.from(Clock.System.now().toJavaInstant())

            // Build WHERE conditions from keys
            val conditions = keys.map { key ->
                if (key.correlationValuesJson == null) {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ?)"
                } else {
                    "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ? AND ($CORRELATION_VALUES_COLUMN IS NULL OR $CORRELATION_VALUES_COLUMN = ?))"
                }
            }

            // Use database-specific JSON aggregation function with ORDER BY filter_index
            val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

            val sql = """
                UPDATE $tableName l
                SET $EVENT_COLUMN = (
                    SELECT $jsonAgg
                    FROM $LISTENER_EVENT_TABLE e
                    WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                ),
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND $TOTAL_FILTERS_COLUMN IS NOT NULL
                  AND (SELECT COUNT(*) FROM $LISTENER_EVENT_TABLE e WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN) >= $TOTAL_FILTERS_COLUMN
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                for (key in keys) {
                    stmt.setString(idx++, key.workflowInfo.workflowNamespace.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowName.toString())
                    stmt.setString(idx++, key.workflowInfo.workflowVersion.toString())
                    stmt.setString(idx++, key.position.toString())
                    if (key.correlationValuesJson != null) {
                        stmt.setString(idx++, key.correlationValuesJson)
                    }
                }
                stmt.executeUpdate()
            }
        }
    }

    // ========================================
    // Streaming Methods for Memory-Efficient Processing
    // ========================================

    /**
     * Data class for streaming listener with accumulated events.
     */
    data class ListenerWithEvents(
        val listener: ListenerModel,
        val accumulatedEvents: List<String>
    )

    /**
     * Streams listeners with their accumulated events for expression evaluation.
     *
     * Uses cursor-based streaming to process listeners one at a time without loading
     * all into memory. This is critical for handling cases where millions of listeners
     * may match.
     *
     * The query JOINs listeners with their accumulated events from lemline_listener_events,
     * aggregating events into a JSON array per listener.
     *
     * ## Memory Efficiency
     *
     * - Uses small fetchSize to stream results one at a time
     * - Each emitted ListenerWithEvents is processed and discarded
     * - Constant memory regardless of total matching listeners
     *
     * ## Connection Lifecycle
     *
     * The connection is held open for the duration of the flow collection.
     * Callers should process items quickly to avoid holding connections too long.
     *
     * @param keys List of query keys identifying listeners to stream
     * @param fetchSize Number of rows to fetch per network round-trip (default: 100)
     * @return Flow of listeners with their accumulated events
     */
    fun streamListenersWithEvents(
        keys: List<ListenerQueryKey>,
        fetchSize: Int = 500
    ): Flow<ListenerWithEvents> = flow {
        if (keys.isEmpty()) return@flow

        databaseManager.datasource.connection.use { conn ->
            // Disable auto-commit for cursor to work
            conn.autoCommit = false

            try {
                // Build WHERE conditions from keys
                val conditions = keys.map { key ->
                    if (key.correlationValuesJson == null) {
                        "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ?)"
                    } else {
                        "($WORKFLOW_NAMESPACE_COLUMN = ? AND $WORKFLOW_NAME_COLUMN = ? AND $WORKFLOW_VERSION_COLUMN = ? AND $WORKFLOW_POSITION_COLUMN = ? AND ($CORRELATION_VALUES_COLUMN IS NULL OR $CORRELATION_VALUES_COLUMN = ?))"
                    }
                }

                // Use database-specific JSON aggregation
                val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

                val sql = """
                    SELECT l.*,
                           (SELECT $jsonAgg
                            FROM $LISTENER_EVENT_TABLE e
                            WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN) as accumulated_events
                    FROM $tableName l
                    WHERE l.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                      AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                      AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                      AND (${conditions.joinToString(" OR ")})
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    // Set small fetch size for cursor streaming
                    stmt.fetchSize = fetchSize

                    var idx = 1
                    for (key in keys) {
                        stmt.setString(idx++, key.workflowInfo.workflowNamespace.toString())
                        stmt.setString(idx++, key.workflowInfo.workflowName.toString())
                        stmt.setString(idx++, key.workflowInfo.workflowVersion.toString())
                        stmt.setString(idx++, key.position.toString())
                        if (key.correlationValuesJson != null) {
                            stmt.setString(idx++, key.correlationValuesJson)
                        }
                    }

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val listener = createModel(rs)
                            val eventsJson = rs.getString("accumulated_events")
                            val events = parseJsonArrayToList(eventsJson)
                            emit(ListenerWithEvents(listener, events))
                        }
                    }
                }
            } finally {
                // Reset auto-commit
                conn.autoCommit = true
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parses a JSON array of strings (from json_agg/JSON_ARRAYAGG) into a List<String>.
     *
     * The JSON may look like: ["event1_json", "event2_json"] or be null if no events.
     */
    private fun parseJsonArrayToList(json: String?): List<String> {
        if (json == null || json == "null" || json.isBlank()) {
            return emptyList()
        }
        return try {
            // The events are already JSON strings inside the array
            // We need to parse them as raw strings, not as nested JSON
            Json.decodeFromString<List<String>>(json)
        } catch (e: Exception) {
            // If parsing fails, return empty list
            emptyList()
        }
    }
}
