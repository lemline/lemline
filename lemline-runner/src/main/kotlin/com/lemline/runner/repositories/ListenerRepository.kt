// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
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
import kotlinx.serialization.ExperimentalSerializationApi

const val LISTENER_TABLE = "lemline_listeners"

/**
 * Repository for managing listener instances in the outbox pattern.
 *
 * This repository handles:
 * - CRUD operations for listeners
 * - Finding matching listeners for CloudEvent routing
 * - Tracking accumulated events and matched filter indices
 * - Timeout detection
 *
 * @see ListenerModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerRepository : OutboxRepository<ListenerModel>() {

    companion object Companion {
        const val LISTEN_DEFINITION_ID_COLUMN = "listen_definition_id"
        const val TIMEOUT_AT_COLUMN = "timeout_at"
        const val CORRELATION_VALUES_COLUMN = "correlation_values"
        const val ACCUMULATED_EVENTS_COLUMN = "accumulated_events"
        const val MATCHED_FILTER_INDICES_COLUMN = "matched_filter_indices"
        const val UPDATED_AT_COLUMN = "updated_at"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, ListenerModel, Int) -> Unit> by lazy {
        // Remove workflow info columns that are not in the listeners table
        // (workflow info is stored in workflow_state JSON as part of InstanceMessage)
        (super.prepareStatementMap - setOf(
            WORKFLOW_NAMESPACE_COLUMN,
            WORKFLOW_NAME_COLUMN,
            WORKFLOW_VERSION_COLUMN
        )) + mapOf(
            // Override WORKFLOW_STATE_COLUMN to store the full InstanceMessage (includes workflowInfo)
            WORKFLOW_STATE_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage.toJsonString())
            },
            LISTEN_DEFINITION_ID_COLUMN to { stmt, entity, idx ->
                setIDV7(stmt, idx, entity.listenDefinitionId)
            },
            TIMEOUT_AT_COLUMN to { stmt, entity, idx ->
                entity.timeoutAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            },
            CORRELATION_VALUES_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.correlationValues)
            },
            ACCUMULATED_EVENTS_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.accumulatedEvents)
            },
            MATCHED_FILTER_INDICES_COLUMN to { stmt, entity, idx ->
                stmt.setString(idx, entity.matchedFilterIndices)
            }
        )
    }

    override fun createModel(rs: ResultSet): ListenerModel = ListenerModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        listenDefinitionId = getIDV7(rs, LISTEN_DEFINITION_ID_COLUMN)!!,
        // Deserialize full InstanceMessage (includes workflowInfo) from workflow_state column
        instanceMessage = InstanceMessage.fromJsonString(rs.getString(WORKFLOW_STATE_COLUMN)),
        workflowId = WorkflowId(getIDV7(rs, WORKFLOW_ID_COLUMN)!!),
        workflowPosition = NodePosition(rs.getString(WORKFLOW_POSITION_COLUMN)),
        timeoutAt = rs.getInstant(TIMEOUT_AT_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    ).apply {
        correlationValues = rs.getString(CORRELATION_VALUES_COLUMN)
        accumulatedEvents = rs.getString(ACCUMULATED_EVENTS_COLUMN)
        matchedFilterIndices = rs.getString(MATCHED_FILTER_INDICES_COLUMN)
        outboxDelayedUntil = rs.getInstant(OUTBOX_DELAYED_UNTIL_COLUMN)
        outboxAttemptCount = rs.getInt(OUTBOX_ATTEMPT_COUNT_COLUMN)
        outboxErrorClass = rs.getString(OUTBOX_ERROR_CLASS_COLUMN)
        outboxErrorMessage = rs.getString(OUTBOX_ERROR_MESSAGE_COLUMN)
        outboxErrorStackTrace = rs.getString(OUTBOX_ERROR_STACKTRACE_COLUMN)
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN)
        outboxFailedAt = rs.getInstant(OUTBOX_FAILED_AT_COLUMN)
    }

    /**
     * Finds active listeners by listen definition ID.
     * Used in CloudEvent routing to find listeners for a specific listen task.
     *
     * @param listenDefinitionId The listen task definition ID
     * @param connection Optional database connection
     * @return List of matching active listeners
     */
    suspend fun findByListenDefinitionId(
        listenDefinitionId: IDV7,
        connection: Connection? = null
    ): List<ListenerModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByListenDefinitionIdSql).use { stmt ->
            setIDV7(stmt, 1, listenDefinitionId)
            stmt.executeQuery().use { it.toModels() }
        }
    }

    private val findByListenDefinitionIdSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND $OUTBOX_FAILED_AT_COLUMN IS NULL
          AND $LISTEN_DEFINITION_ID_COLUMN = ?
        """.trimIndent()
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
     * Updates listener progress after receiving an event.
     * Used for ALL strategy (tracking matched indices) and ANY with until (accumulating events).
     *
     * @param id Listener ID
     * @param accumulatedEvents JSON array of accumulated events (for ANY with until)
     * @param matchedFilterIndices JSON array of matched filter indices (for ALL)
     * @param correlationValues JSON map of correlation baseline values (Mode 2)
     * @param connection Optional database connection
     * @return Number of rows updated
     */
    suspend fun updateProgress(
        id: IDV7,
        accumulatedEvents: String?,
        matchedFilterIndices: String?,
        correlationValues: String?,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(updateProgressSql).use { stmt ->
            stmt.setString(1, accumulatedEvents)
            stmt.setString(2, matchedFilterIndices)
            stmt.setString(3, correlationValues)
            stmt.setTimestamp(4, Timestamp.from(Clock.System.now().toJavaInstant()))
            setIDV7(stmt, 5, id)
            stmt.executeUpdate()
        }
    }

    private val updateProgressSql by lazy {
        """
        UPDATE $tableName
        SET $ACCUMULATED_EVENTS_COLUMN = ?,
            $MATCHED_FILTER_INDICES_COLUMN = ?,
            $CORRELATION_VALUES_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $ID_COLUMN = ?
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
     * Finds a listener by ID and locks it for update.
     * Used for optimistic concurrency control in CloudEvent processing.
     *
     * @param id Listener ID
     * @param connection Database connection (required for transaction)
     * @return The locked listener or null if not found
     */
    suspend fun findByIdForUpdate(id: IDV7, connection: Connection): ListenerModel? =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByIdForUpdateSql).use { stmt ->
                setIDV7(stmt, 1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByIdForUpdateSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $ID_COLUMN = ?
        FOR UPDATE
        """.trimIndent()
    }
}
