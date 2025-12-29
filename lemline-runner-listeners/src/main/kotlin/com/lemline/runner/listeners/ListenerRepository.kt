// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
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
import com.lemline.runner.common.repositories.ops.OUTBOX_COMPLETED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_DELAYED_UNTIL_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_CLASS_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_MESSAGE_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_ERROR_STACKTRACE_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_FAILED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.common.repositories.ops.OutboxRepository
import com.lemline.runner.common.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_VERSION_COLUMN
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstanceMessage
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.ops.instanceColumns
import com.lemline.runner.common.repositories.ops.nowTimestamp
import com.lemline.runner.common.repositories.ops.outboxColumns
import com.lemline.runner.common.repositories.ops.readCleanupField
import com.lemline.runner.common.repositories.ops.readOutboxFields
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.listeners.ListenerEventRepository.Companion.FOREACH_COMPLETED_COLUMN
import com.lemline.runner.listeners.ListenerEventRepository.Companion.LISTENER_ID_COLUMN
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
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
 * - `completed_at`: Set when listener stops collecting events
 * - Standard outbox columns for processing
 *
 * @see ListenerModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
class ListenerRepository : CrudRepository<ListenerModel>(),
    WithIdRepository<ListenerModel>,
    WithOutboxRepository<ListenerModel>,
    WithCleanerRepository<ListenerModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = LISTENER_TABLE

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
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
        const val CLOSED_AT_COLUMN = "closed_at"
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
            column(CLOSED_AT_COLUMN) { stmt, entity, idx ->
                entity.closedAt?.let {
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
        closedAt = rs.getInstant(CLOSED_AT_COLUMN)
    }
        .readOutboxFields(rs)
        .readCleanupField(rs)

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
     * Batch marks listeners as ready for processing.
     *
     * This method finds listeners where:
     * - Listener is closed (closed_at IS NOT NULL)
     * - All events have completed foreach processing (no events with foreach_completed = false)
     * - outbox_delayed_until IS NULL
     *
     * For those listeners, sets outbox_delayed_until = now to mark them ready for processing.
     *
     * @return Number of listeners marked as ready
     */
    suspend fun batchPrepareListenerOutbox(connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()
            val sql = """
                UPDATE $tableName l
                SET $OUTBOX_SCHEDULED_FOR_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$CLOSED_AT_COLUMN IS NOT NULL
                  AND l.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM $LISTENER_EVENTS_TABLE e
                      WHERE e.$LISTENER_ID_COLUMN = l.$ID_COLUMN
                        AND NOT e.$FOREACH_COMPLETED_COLUMN
                  )
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
                stmt.setTimestamp(3, now)
                stmt.executeUpdate()
            }
        }

    /**
     * Marks listeners as completed by termination event (ANY_UNTIL_EVENT strategy).
     * Called when a termination event is received.
     *
     * This method only sets `completed_at` to mark that termination was received (listener stops collecting events).
     * The `outbox_delayed_until` will be set later by `batchMarkCompletedTerminatedListeners()`
     * when all foreach events are processed.
     */
    suspend fun markListenerCompletedByUntilEvent(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        // Filter keys to only include ANY_UNTIL_EVENT strategy - skip query if none
        val untilEventKeys = keys.filter { it.listenerStrategy == ListenerStrategy.ANY_UNTIL_EVENT }
        if (untilEventKeys.isEmpty()) return 0

        return databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()

            // Mark completed_at for matched listeners (termination received)
            // Note: outbox_delayed_until is NOT set here - done later when foreach events complete
            val sql = """
                UPDATE $tableName l
                SET $CLOSED_AT_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$CLOSED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(untilEventKeys, "l")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, now)  // completed_at
                stmt.setTimestamp(2, now)  // updated_at
                ListenerQueryKey.bindAllParameters(untilEventKeys, stmt, 3)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Finds ANY_UNTIL_EXPR listeners matching the given query keys with their accumulated events.
     * Used by ListenerEventService immediately after an event is inserted.
     *
     * @param keys Query keys from matching listen tasks (must include ANY_UNTIL_EXPR strategy)
     * @return Pairs of (listener, accumulated events as JSON strings)
     */
    suspend fun findListenersByKeysWithEvents(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): List<Pair<ListenerModel, List<String>>> {
        // Filter keys to only include ANY_UNTIL_EXPR strategy
        val exprKeys = keys.filter { it.listenerStrategy == ListenerStrategy.ANY_UNTIL_EXPR }
        if (exprKeys.isEmpty()) return emptyList()

        return databaseConfig.withConnection(connection) { conn ->
            val eventsSubquery = databaseConfig.jsonArrayAggOrderedSubquery(
                table = LISTENER_EVENTS_TABLE,
                tableAlias = "e",
                column = "e.${ListenerEventRepository.EVENT_COLUMN}",
                orderColumn = "e.$CREATED_AT_COLUMN",
                whereClause = "e.$LISTENER_ID_COLUMN = l.$ID_COLUMN"
            )

            val sql = """
                SELECT l.*, $eventsSubquery as events_json
                FROM $tableName l
                WHERE l.$CLOSED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(exprKeys, "l")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                ListenerQueryKey.bindAllParameters(exprKeys, stmt, 1)
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
    }

    /**
     * Batch marks multiple listeners as completed (after until expression evaluation).
     * Uses a single UPDATE with IN clause for efficiency.
     *
     * @param ids List of listener IDs to mark as completed
     * @return Number of listeners actually marked as completed
     */
    suspend fun batchMarkListenersCompleted(
        ids: List<IDV7>,
        connection: Connection? = null
    ): Int {
        if (ids.isEmpty()) return 0

        return databaseConfig.withConnection(connection) { conn ->
            val now = nowTimestamp()
            val placeholders = ids.joinToString(", ") { "?" }
            val sql = """
                UPDATE $tableName
                SET $CLOSED_AT_COLUMN = ?, $UPDATED_AT_COLUMN = ?
                WHERE $ID_COLUMN IN ($placeholders)
                  AND $CLOSED_AT_COLUMN IS NULL
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, now)
                stmt.setTimestamp(2, now)
                ids.forEachIndexed { index, id ->
                    setIDV7(stmt, 3 + index, id)
                }
                stmt.executeUpdate()
            }
        }
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
