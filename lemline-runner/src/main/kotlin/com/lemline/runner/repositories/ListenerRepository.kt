// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.models.ListenerStrategy
import com.lemline.runner.repositories.helpers.ColumnBindings
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.ops.CleanerRepository
import com.lemline.runner.repositories.ops.CrudRepository
import com.lemline.runner.repositories.ops.ID_COLUMN
import com.lemline.runner.repositories.ops.IdRepository
import com.lemline.runner.repositories.ops.InstanceRepository
import com.lemline.runner.repositories.ops.OUTBOX_COMPLETED_AT_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_DELAYED_UNTIL_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_ERROR_CLASS_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_ERROR_MESSAGE_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_ERROR_STACKTRACE_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_FAILED_AT_COLUMN
import com.lemline.runner.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.repositories.ops.OutboxRepository
import com.lemline.runner.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.repositories.ops.WORKFLOW_ID_COLUMN
import com.lemline.runner.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.repositories.ops.WORKFLOW_POSITION_COLUMN
import com.lemline.runner.repositories.ops.WORKFLOW_VERSION_COLUMN
import com.lemline.runner.repositories.ops.cleanupColumns
import com.lemline.runner.repositories.ops.getInstanceMessage
import com.lemline.runner.repositories.ops.getInstant
import com.lemline.runner.repositories.ops.idColumn
import com.lemline.runner.repositories.ops.instanceColumns
import com.lemline.runner.repositories.ops.outboxColumns
import com.lemline.runner.repositories.ops.readCleanupField
import com.lemline.runner.repositories.ops.readOutboxFields
import com.lemline.runner.repositories.with.WithCleanerRepository
import com.lemline.runner.repositories.with.WithIdRepository
import com.lemline.runner.repositories.with.WithInstanceRepository
import com.lemline.runner.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

const val LISTENER_TABLE = "lemline_listeners"

/**
 * Key for batch querying listeners by workflow identity and correlation.
 */
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
data class ListenerQueryKey(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val correlationValuesJson: String?
) {
    /**
     * Builds SQL WHERE condition for this key.
     */
    fun toSqlCondition(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return if (correlationValuesJson == null) {
            "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ?)"
        } else {
            "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ? AND (${prefix}${ListenerRepository.CORRELATION_VALUES_COLUMN} IS NULL OR ${prefix}${ListenerRepository.CORRELATION_VALUES_COLUMN} = ?))"
        }
    }

    /**
     * Binds this key's parameters to a PreparedStatement starting at the given index.
     */
    fun bindParameters(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        if (correlationValuesJson != null) {
            stmt.setString(idx++, correlationValuesJson)
        }
        return idx
    }

    /**
     * Builds SQL WHERE condition without correlation check (used for termination events).
     */
    fun toSqlConditionWithoutCorrelation(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ?)"
    }

    /**
     * Binds parameters without correlation value (used for termination events).
     */
    fun bindParametersWithoutCorrelation(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        return idx
    }

    companion object {
        /**
         * Builds combined WHERE clause for multiple keys using OR.
         */
        fun buildWhereClause(keys: List<ListenerQueryKey>, tableAlias: String = ""): String =
            keys.joinToString(" OR ") { it.toSqlCondition(tableAlias) }

        /**
         * Binds parameters for all keys to a PreparedStatement.
         */
        fun bindAllParameters(keys: List<ListenerQueryKey>, stmt: PreparedStatement, startIndex: Int): Int {
            var idx = startIndex
            for (key in keys) {
                idx = key.bindParameters(stmt, idx)
            }
            return idx
        }
    }
}

/**
 * Repository for managing listener instances in the outbox pattern.
 * Uses composition pattern with column bindings.
 *
 * @see ListenerModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerRepository : CrudRepository<ListenerModel>(),
    WithIdRepository<ListenerModel>,
    WithInstanceRepository<ListenerModel>,
    WithOutboxRepository<ListenerModel>,
    WithCleanerRepository<ListenerModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_TABLE

    // Composed operations - initialized lazily to ensure databaseManager is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseManager) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseManager) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseManager) }

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

        return withConnection(connection) { conn ->
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
        const val EVENT_COLUMN = "event"
        const val FILTERS_COUNT_COLUMN = "filters_count"

        // Foreach columns
        const val HAS_FOREACH_COLUMN = "has_foreach"
        const val FOREACH_CURRENT_INDEX_COLUMN = "foreach_current_index"
        const val FOREACH_PROCESSING_COLUMN = "foreach_processing"
        const val LISTENER_COMPLETED_COLUMN = "listener_completed"

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
                stmt.setString(idx, entity.strategy.name)
            }
            column(TIMEOUT_AT_COLUMN) { stmt, entity, idx ->
                entity.timeoutAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column(CORRELATION_VALUES_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.correlationValues)
            }
            column(EVENT_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.event)
            }
            column(FILTERS_COUNT_COLUMN) { stmt, entity, idx ->
                entity.filtersCount?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }

            // Foreach columns
            column(HAS_FOREACH_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.hasForeach)
            }
            column(FOREACH_CURRENT_INDEX_COLUMN) { stmt, entity, idx ->
                stmt.setInt(idx, entity.foreachCurrentIndex)
            }
            column(FOREACH_PROCESSING_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.foreachProcessing)
            }
            column(LISTENER_COMPLETED_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.listenerCompleted)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet): ListenerModel = ListenerModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ListenStarted>(idHelper)!!,
        strategy = ListenerStrategy.valueOf(rs.getString(STRATEGY_COLUMN)),
        timeoutAt = rs.getInstant(TIMEOUT_AT_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    ).apply {
        // Listener-specific columns
        correlationValues = rs.getString(CORRELATION_VALUES_COLUMN)
        event = rs.getString(EVENT_COLUMN)
        filtersCount = rs.getInt(FILTERS_COUNT_COLUMN).takeIf { !rs.wasNull() }
        // Foreach columns
        hasForeach = rs.getBoolean(HAS_FOREACH_COLUMN)
        foreachCurrentIndex = rs.getInt(FOREACH_CURRENT_INDEX_COLUMN)
        foreachProcessing = rs.getBoolean(FOREACH_PROCESSING_COLUMN)
        listenerCompleted = rs.getBoolean(LISTENER_COMPLETED_COLUMN)
    }
        .readCleanupField(rs)
        .readOutboxFields(rs)

    /**
     * Batch finds active listeners for multiple query keys in a single database round-trip.
     */
    suspend fun findByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): List<ListenerModel> {
        if (keys.isEmpty()) return emptyList()

        return withConnection(connection) { conn ->
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
        withConnection(connection) { conn ->
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
        withConnection(connection) { conn ->
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
    ): Int = withConnection(connection) { conn ->
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
     */
    suspend fun tryMarkReadyForCompletion(
        id: IDV7,
        event: String,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = nowTimestamp()

        conn.prepareStatement(markReadyForCompletionSql).use { stmt ->
            stmt.setString(1, event)
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
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
     */
    suspend fun markReadyForCompletionFromEvents(
        id: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = nowTimestamp()

        conn.prepareStatement(markReadyForCompletionFromEventsSql).use { stmt ->
            stmt.setTimestamp(1, now)
            stmt.setTimestamp(2, now)
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
     */
    suspend fun markReadyForCompletionByKeys(
        keys: List<ListenerQueryKey>,
        event: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()

            val sql = """
                UPDATE $tableName
                SET $EVENT_COLUMN = ?,
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND (${ListenerQueryKey.buildWhereClause(keys)})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, event)
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Batch marks listeners as ready for completion (for ALL/ANY+until).
     */
    suspend fun batchMarkReadyForCompletionFromEvents(
        listenerEvents: Map<IDV7, String>,
        connection: Connection? = null
    ): Int {
        if (listenerEvents.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()
            val ids = listenerEvents.keys.toList()
            val placeholders = ids.joinToString(", ") { "?" }
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

                for ((id, event) in listenerEvents) {
                    setIDV7(stmt, paramIndex++, id)
                    stmt.setString(paramIndex++, event)
                }

                stmt.setTimestamp(paramIndex++, now)
                stmt.setTimestamp(paramIndex++, now)

                for (id in ids) {
                    setIDV7(stmt, paramIndex++, id)
                }

                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks listeners as terminated by query keys (ANY + until(event) strategy).
     * Only affects non-foreach listeners (has_foreach = FALSE).
     */
    suspend fun markTerminatedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()
            val conditions = keys.map { it.toSqlConditionWithoutCorrelation() }
            val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

            val sql = """
                UPDATE $tableName l
                SET $EVENT_COLUMN = (
                    SELECT $jsonAgg
                    FROM $LISTENER_EVENT_TABLE e
                    WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.${ID_COLUMN}
                ),
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND $HAS_FOREACH_COLUMN = FALSE
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                for (key in keys) {
                    idx = key.bindParametersWithoutCorrelation(stmt, idx)
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks foreach-enabled listeners as logically completed (ANY + until(event) strategy).
     * Sets listener_completed = TRUE without setting outbox_delayed_until.
     * The ListenerEventOutbox will complete the listener after all foreach iterations.
     */
    suspend fun markForeachTerminatedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()
            val conditions = keys.map { it.toSqlConditionWithoutCorrelation("l") }

            val sql = """
                UPDATE $tableName l
                SET $LISTENER_COMPLETED_COLUMN = TRUE,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$HAS_FOREACH_COLUMN = TRUE
                  AND (${conditions.joinToString(" OR ")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                for (key in keys) {
                    idx = key.bindParametersWithoutCorrelation(stmt, idx)
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks listeners as completed by query keys (ALL strategy).
     * Only affects non-foreach listeners (has_foreach = FALSE).
     */
    suspend fun markAllCompletedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()
            val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

            val sql = """
                UPDATE $tableName l
                SET $EVENT_COLUMN = (
                    SELECT $jsonAgg
                    FROM $LISTENER_EVENT_TABLE e
                    WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.${ID_COLUMN}
                ),
                    $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
                    $UPDATED_AT_COLUMN = ?
                WHERE $OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND $FILTERS_COUNT_COLUMN IS NOT NULL
                  AND $HAS_FOREACH_COLUMN = FALSE
                  AND (SELECT COUNT(*) FROM $LISTENER_EVENT_TABLE e WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.${ID_COLUMN}) >= $FILTERS_COUNT_COLUMN
                  AND (${ListenerQueryKey.buildWhereClause(keys)})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                stmt.setTimestamp(idx++, now)
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks foreach-enabled listeners as logically completed (ALL strategy).
     * Sets listener_completed = TRUE without setting outbox_delayed_until.
     * The ListenerEventOutbox will complete the listener after all foreach iterations.
     */
    suspend fun markForeachAllCompletedByKeys(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        return withConnection(connection) { conn ->
            val now = nowTimestamp()

            val sql = """
                UPDATE $tableName l
                SET $LISTENER_COMPLETED_COLUMN = TRUE,
                    $UPDATED_AT_COLUMN = ?
                WHERE l.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                  AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND l.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND l.$FILTERS_COUNT_COLUMN IS NOT NULL
                  AND l.$HAS_FOREACH_COLUMN = TRUE
                  AND (SELECT COUNT(*) FROM $LISTENER_EVENT_TABLE e WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.${ID_COLUMN}) >= l.$FILTERS_COUNT_COLUMN
                  AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setTimestamp(idx++, now)
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
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
     */
    fun streamListenersWithEvents(
        keys: List<ListenerQueryKey>,
        fetchSize: Int = 500
    ): Flow<ListenerWithEvents> = flow {
        if (keys.isEmpty()) return@flow

        databaseManager.datasource.connection.use { conn ->
            conn.autoCommit = false

            try {
                val jsonAgg = databaseManager.jsonArrayAgg("e.${ListenerEventRepository.EVENT_COLUMN}")

                val sql = """
                    SELECT l.*,
                           (SELECT $jsonAgg
                            FROM $LISTENER_EVENT_TABLE e
                            WHERE e.${ListenerEventRepository.LISTENER_ID_COLUMN} = l.${ID_COLUMN}) as accumulated_events
                    FROM $tableName l
                    WHERE l.${OUTBOX_DELAYED_UNTIL_COLUMN} IS NULL
                      AND l.${OUTBOX_COMPLETED_AT_COLUMN} IS NULL
                      AND l.${OUTBOX_FAILED_AT_COLUMN} IS NULL
                      AND (${ListenerQueryKey.buildWhereClause(keys, "l")})
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.fetchSize = fetchSize
                    ListenerQueryKey.bindAllParameters(keys, stmt, 1)

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
                conn.autoCommit = true
            }
        }
    }.flowOn(Dispatchers.IO)

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
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ========================================
    // Foreach Methods
    // ========================================

    /**
     * Finds a listener by workflow ID and position.
     */
    suspend fun findByWorkflowIdAndPosition(
        workflowId: WorkflowId,
        position: NodePosition,
        connection: Connection? = null
    ): ListenerModel? = withConnection(connection) { conn ->
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
     * Sets the foreach_processing flag for a listener.
     */
    suspend fun setForeachProcessing(
        id: IDV7,
        processing: Boolean,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(setForeachProcessingSql).use { stmt ->
            stmt.setBoolean(1, processing)
            stmt.setTimestamp(2, nowTimestamp())
            setIDV7(stmt, 3, id)
            stmt.executeUpdate()
        }
    }

    private val setForeachProcessingSql by lazy {
        """
        UPDATE $tableName
        SET $FOREACH_PROCESSING_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Increments the foreach_current_index for a listener.
     */
    suspend fun incrementForeachIndex(
        id: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(incrementForeachIndexSql).use { stmt ->
            stmt.setTimestamp(1, nowTimestamp())
            setIDV7(stmt, 2, id)
            stmt.executeUpdate()
        }
    }

    private val incrementForeachIndexSql by lazy {
        """
        UPDATE $tableName
        SET $FOREACH_CURRENT_INDEX_COLUMN = $FOREACH_CURRENT_INDEX_COLUMN + 1,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Sets the listener_completed flag for a listener.
     */
    suspend fun setListenerCompleted(
        id: IDV7,
        completed: Boolean,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(setListenerCompletedSql).use { stmt ->
            stmt.setBoolean(1, completed)
            stmt.setTimestamp(2, nowTimestamp())
            setIDV7(stmt, 3, id)
            stmt.executeUpdate()
        }
    }

    private val setListenerCompletedSql by lazy {
        """
        UPDATE $tableName
        SET $LISTENER_COMPLETED_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Marks listener ready for completion with aggregated event output.
     */
    suspend fun markReadyForCompletionWithOutput(
        id: IDV7,
        event: String,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        val now = nowTimestamp()
        conn.prepareStatement(markReadyForCompletionWithOutputSql).use { stmt ->
            stmt.setString(1, event)
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
            setIDV7(stmt, 4, id)
            stmt.executeUpdate()
        }
    }

    private val markReadyForCompletionWithOutputSql by lazy {
        """
        UPDATE $tableName
        SET $EVENT_COLUMN = ?,
            $OUTBOX_DELAYED_UNTIL_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
        """.trimIndent()
    }
}
