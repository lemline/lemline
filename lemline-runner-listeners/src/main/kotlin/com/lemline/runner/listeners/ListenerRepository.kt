// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CREATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.CleanerRepository
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.InstanceRepository
import com.lemline.runner.common.repositories.ops.OUTBOX_COMPLETED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_DELAYED_UNTIL_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_CLASS_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_MESSAGE_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_STACKTRACE_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_FAILED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.common.repositories.ops.OutboxRepository
import com.lemline.runner.common.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_ID_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_POSITION_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_VERSION_COLUMN
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstanceMessage
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.ops.instanceColumns
import com.lemline.runner.common.repositories.ops.outboxColumns
import com.lemline.runner.common.repositories.ops.readCleanupField
import com.lemline.runner.common.repositories.ops.readOutboxFields
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import com.lemline.runner.common.repositories.with.WithInstanceRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

const val LISTENER_TABLE = "lemline_listeners"

/**
 * Repository for managing listener instances in the outbox pattern.
 * Uses composition pattern with column bindings.
 *
 * ## Simplified Architecture
 *
 * All events are stored in `lemline_listener_events` table.
 * State tracking uses:
 * - `ready_at`: Set when completion criteria are met
 * - Standard outbox columns for processing
 *
 * @see ListenerModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
class ListenerRepository : CrudRepository<ListenerModel>(),
    WithIdRepository<ListenerModel>,
    WithInstanceRepository<ListenerModel>,
    WithOutboxRepository<ListenerModel>,
    WithCleanerRepository<ListenerModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = LISTENER_TABLE

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    /**
     * Batch finds listeners by their IDs in a single query.
     * Returns a map of ID to ListenerModel for efficient lookup.
     */
    suspend fun findByIds(
        ids: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, ListenerModel> {
        if (ids.isEmpty()) return emptyMap()

        return databaseConfig.withConnection(connection) { conn ->
            val uniqueIds = ids.distinct()
            val placeholders = uniqueIds.joinToString(", ") { "?" }
            val sql = "SELECT * FROM $tableName WHERE $ID_COLUMN IN ($placeholders)"

            conn.prepareStatement(sql).use { stmt ->
                uniqueIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val model = createModel(rs)
                            put(model.id, model)
                        }
                    }
                }
            }
        }
    }

    // Delegate WithInstanceRepository methods
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        instanceRepository.findByWorkflowId(workflowId, connection)

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    companion object {
        const val STRATEGY_COLUMN = "strategy"
        const val TIMEOUT_AT_COLUMN = "timeout_at"
        const val CORRELATION_VALUES_COLUMN = "correlation_values"
        const val FILTERS_COUNT_COLUMN = "filters_count"
        const val HAS_UNTIL_COLUMN = "has_until"
        const val UNTIL_EXPRESSION_COLUMN = "until_expression"
        const val HAS_FOREACH_COLUMN = "has_foreach"
        const val READY_AT_COLUMN = "ready_at"

        /** Creates a current timestamp for database operations. */
        private fun nowTimestamp(): Timestamp = Timestamp.from(Clock.System.now().toJavaInstant())
    }

    override val columns: ColumnBindings<ListenerModel> by lazy {
        ColumnBindingsBuilder<ListenerModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            cleanupColumns()
            outboxColumns()

            // Listener-specific columns
            column(STRATEGY_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.listenerStrategy.name)
            }
            column(TIMEOUT_AT_COLUMN) { stmt, entity, idx ->
                entity.timeoutAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column(FILTERS_COUNT_COLUMN) { stmt, entity, idx ->
                entity.filtersCount?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
            column(HAS_UNTIL_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.hasUntil)
            }
            column(UNTIL_EXPRESSION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.untilExpression)
            }
            column(HAS_FOREACH_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.hasForeach)
            }
            column(CORRELATION_VALUES_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.correlationValues)
            }
            column(READY_AT_COLUMN) { stmt, entity, idx ->
                entity.readyAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet): ListenerModel = ListenerModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ListenStarted>(idHelper)!!,
        listenerStrategy = ListenerStrategy.valueOf(rs.getString(STRATEGY_COLUMN)),
        timeoutAt = rs.getInstant(TIMEOUT_AT_COLUMN),
    ).apply {
        // Listener-specific columns
        filtersCount = rs.getInt(FILTERS_COUNT_COLUMN).takeIf { !rs.wasNull() }
        hasUntil = rs.getBoolean(HAS_UNTIL_COLUMN)
        untilExpression = rs.getString(UNTIL_EXPRESSION_COLUMN)
        hasForeach = rs.getBoolean(HAS_FOREACH_COLUMN)
        correlationValues = rs.getString(CORRELATION_VALUES_COLUMN)
        readyAt = rs.getInstant(READY_AT_COLUMN)
    }
        .readOutboxFields(rs)
        .readCleanupField(rs)

    /**
     * Batch finds active listeners for multiple query keys in a single database round-trip.
     */
    suspend fun findByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): List<ListenerModel> {
        if (keys.isEmpty()) return emptyList()

        return databaseConfig.withConnection(connection) { conn ->
            val sql = """
                SELECT * FROM $tableName
                WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(keys)})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                ListenerQueryKey.bindAllParameters(keys, stmt, 1)
                stmt.executeQuery().use { it.toModels() }
            }
        }
    }

    /**
     * Finds listeners that have timed out.
     */
    suspend fun findTimedOut(limit: Int, connection: Connection? = null): List<ListenerModel> =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(findTimedOutSql).use { stmt ->
                stmt.setTimestamp(1, nowTimestamp())
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
     */
    suspend fun markCompleted(id: IDV7, connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(markCompletedSql).use { stmt ->
                val now = nowTimestamp()
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
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
     */
    suspend fun markFailed(
        id: IDV7,
        errorClass: String?,
        errorMessage: String?,
        errorStackTrace: String?,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(markFailedSql).use { stmt ->
            val now = nowTimestamp()
            stmt.setTimestamp(1, now)
            stmt.setString(2, errorClass)
            stmt.setString(3, errorMessage)
            stmt.setString(4, errorStackTrace)
            stmt.setTimestamp(5, now)
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
     */
    suspend fun deleteByWorkflowDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
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
    // Simplified Completion Methods
    // ========================================

    /**
     * Batch marks listeners as ready for completion.
     *
     * This method checks completion criteria and sets ready_at + outbox_delayed_until:
     * - ONE/ANY: Ready when at least one event with outbox_completed_at IS NOT NULL
     * - ALL: Ready when COUNT(DISTINCT filter_index) >= filters_count AND all completed
     * - ANY_UNTIL_EXPR: Handled separately via evaluateUntilExpression()
     * - ANY_UNTIL_EVENT: Handled via batchMarkReadyByTermination()
     *
     * @return Number of listeners marked ready
     */
    suspend fun batchMarkReady(connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()

            // Mark ONE/ANY listeners ready (at least one completed event)
            val oneAnySql = """
                UPDATE $tableName l
                SET $READY_AT_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$READY_AT_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$STRATEGY_COLUMN IN ('ONE', 'ANY')
                  AND EXISTS (
                      SELECT 1 FROM $LISTENER_EVENTS_TABLE e
                      WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                        AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
                  )
            """.trimIndent()

            val oneAnyCount = conn.prepareStatement(oneAnySql).use { stmt ->
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
                stmt.setTimestamp(3, now)
                stmt.executeUpdate()
            }

            // Mark ALL listeners ready (all filters matched and all events completed)
            val allSql = """
                UPDATE $tableName l
                SET $READY_AT_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$READY_AT_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$STRATEGY_COLUMN = 'ALL'
                  AND l.$FILTERS_COUNT_COLUMN IS NOT NULL
                  AND (
                      SELECT COUNT(DISTINCT e.${ListenerEventRepository.FILTER_INDEX_COLUMN})
                      FROM $LISTENER_EVENTS_TABLE e
                      WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                        AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
                  ) >= l.$FILTERS_COUNT_COLUMN
            """.trimIndent()

            val allCount = conn.prepareStatement(allSql).use { stmt ->
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
                stmt.setTimestamp(3, now)
                stmt.executeUpdate()
            }

            oneAnyCount + allCount
        }

    /**
     * Marks listeners as ready by termination event (ANY_UNTIL_EVENT strategy).
     * Called when a termination event is received.
     *
     * This method sets `ready_at` to mark that termination was received.
     * The `outbox_delayed_until` is only set if all events are already completed.
     * For foreach listeners with pending events, `batchMarkReadyTerminatedListeners()`
     * will set `outbox_delayed_until` later when all events are done.
     */
    suspend fun batchMarkReadyByUntilEvent(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()
            val conditions = keys.map { it.toSqlConditionWithoutCorrelation("l") }

            // First: Mark ready_at for ALL matched listeners (termination received)
            // Only set outbox_delayed_until if all events are completed
            val sql = """
                UPDATE $tableName l
                SET $READY_AT_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = CASE
                        WHEN NOT EXISTS (
                            SELECT 1 FROM $LISTENER_EVENTS_TABLE e
                            WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                              AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                        ) THEN ?
                        ELSE NULL
                    END,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$READY_AT_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$STRATEGY_COLUMN = 'ANY_UNTIL_EVENT'
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)  // ready_at
                stmt.setTimestamp(idx++, now)  // outbox_delayed_until (conditional)
                stmt.setTimestamp(idx++, now)  // updated_at
                for (key in keys) {
                    idx = key.bindParametersWithoutCorrelation(stmt, idx)
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks terminated ANY_UNTIL_EVENT listeners as ready for processing.
     *
     * Called from ListenerCompletionOutbox to check if listeners that received
     * a termination event (ready_at IS NOT NULL) now have all their events
     * completed (for foreach processing).
     *
     * @return Number of listeners marked ready for processing
     */
    suspend fun batchMarkReadyTerminatedListeners(connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()
            conn.prepareStatement(batchMarkReadyTerminatedSql).use { stmt ->
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
                stmt.executeUpdate()
            }
        }

    private val batchMarkReadyTerminatedSql by lazy {
        """
        UPDATE $tableName l
        SET $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE l.$READY_AT_COLUMN IS NOT NULL
          AND l.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
          AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
          AND l.$STRATEGY_COLUMN = 'ANY_UNTIL_EVENT'
          AND NOT EXISTS (
              SELECT 1 FROM $LISTENER_EVENTS_TABLE e
              WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          )
        """.trimIndent()
    }

    /**
     * Finds ANY_UNTIL_EXPR listeners with completed events needing expression evaluation.
     * Returns pairs of (listener, list of event data).
     */
    suspend fun findListenersForUntilEvaluation(
        limit: Int = 100,
        connection: Connection? = null
    ): List<Pair<ListenerModel, List<String>>> =
        databaseConfig.withConnection(connection) { conn ->
            // Use ordered JSON array aggregation (ORDER BY inside aggregate for H2 compatibility)
            val jsonAgg = databaseConfig.jsonArrayAggOrdered(
                "e.${ListenerEventRepository.EVENT_COLUMN}",
                "e.$CREATED_AT_COLUMN"
            )

            val sql = """
                SELECT l.*,
                       (SELECT $jsonAgg
                        FROM $LISTENER_EVENTS_TABLE e
                        WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                          AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
                       ) as events_json
                FROM $tableName l
                WHERE l.$READY_AT_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$STRATEGY_COLUMN = 'ANY_UNTIL_EXPR'
                  AND EXISTS (
                      SELECT 1 FROM $LISTENER_EVENTS_TABLE e
                      WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.$ID_COLUMN
                        AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
                  )
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val listener = createModel(rs)
                            val eventsJson = rs.getString("events_json")
                            val events = parseJsonArrayToList(eventsJson)
                            add(listener to events)
                        }
                    }
                }
            }
        }

    /**
     * Marks a single listener as ready (after until expression evaluation).
     */
    suspend fun markReady(
        id: IDV7,
        connection: Connection? = null
    ): Int = databaseConfig.withConnection(connection) { conn ->
        val now = nowTimestamp()
        conn.prepareStatement(markReadySql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
            setIDV7(stmt, 4, id)
            stmt.executeUpdate()
        }
    }

    private val markReadySql by lazy {
        """
        UPDATE $tableName
        SET $READY_AT_COLUMN = ?,
            $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
          AND $READY_AT_COLUMN IS NULL
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        """.trimIndent()
    }

    /**
     * Finds a listener by workflow ID and position.
     */
    suspend fun findByWorkflowIdAndPosition(
        workflowId: WorkflowId,
        position: NodePosition,
        connection: Connection? = null
    ): ListenerModel? = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(findByWorkflowIdAndPositionSql).use { stmt ->
            stmt.setString(1, workflowId.toString())
            stmt.setString(2, position.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByWorkflowIdAndPositionSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $WORKFLOW_ID_COLUMN = ?
          AND $WORKFLOW_POSITION_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Parses a JSON array from json_agg/JSON_ARRAYAGG into a List<String>.
     */
    private fun parseJsonArrayToList(json: String?): List<String> {
        if (json == null || json == "null" || json.isBlank()) {
            return emptyList()
        }
        return try {
            val array = Json.parseToJsonElement(json).jsonArray
            array.map { Json.encodeToString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
