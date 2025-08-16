// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Base interface for outbox pattern repositories.
 * This interface defines the common operations for managing messages in the outbox pattern,
 * which is used to ensure reliable message delivery in distributed systems.
 *
 * Key features:
 * - Parallel processing safety using SKIP LOCKED
 * - Ordered processing based on timestamps
 * - Batch processing with configurable limits
 * - Automatic cleanup of processed messages
 *
 * Native SQL Queries:
 * This interface uses native SQL queries because Hibernate does not support the SKIP LOCKED feature.
 * While Hibernate provides other locking mechanisms, SKIP LOCKED is essential for our parallel processing
 * requirements as it allows multiple processors to work on different messages simultaneously without blocking.
 *
 * Database Support:
 * The SKIP LOCKED feature is supported by:
 * - PostgreSQL 9.5+
 * - Oracle 10g+
 * - MySQL 8.0+ (with InnoDB)
 * - MariaDB 10.3+ (with InnoDB)
 * - IBM DB2 9.7+
 *
 * Note: SQL Server uses a different syntax (UPDLOCK, READPAST) and is not supported
 *
 * Parallel Processing Safety:
 * The interface uses SKIP LOCKED in native SQL queries to ensure safe parallel processing:
 * 1. Multiple processors can run simultaneously without blocking each other
 * 2. Each processor gets a unique set of messages to process
 * 3. No message is processed by more than one processor at a time
 * 4. Failed locks are skipped, allowing other processors to continue
 * 5. Processing order is maintained within each batch
 *
 * @see OutboxModel for the base message model
 * @see OutboxProcessor for the processing logic
 */
@OptIn(ExperimentalTime::class)
abstract class OutboxRepository<T : OutboxModel> : Repository<T>() {

    companion object {
        internal const val ID_COLUMN = "id"

        internal const val WORKFLOW_ID_COLUMN = "workflow_id"
        internal const val WORKFLOW_NAME_COLUMN = "workflow_name"
        internal const val WORKFLOW_VERSION_COLUMN = "workflow_version"
        internal const val WORKFLOW_POSITION_COLUMN = "workflow_position"
        internal const val WORKFLOW_STATE_COLUMN = "workflow_state"

        internal const val OUTBOX_STATUS_COLUMN = "status"
        internal const val OUTBOX_SCHEDULED_FOR_COLUMN = "scheduled_for"
        internal const val OUTBOX_DELAYED_UNTIL_COLUMN = "delayed_until"
        internal const val OUTBOX_ATTEMPT_COUNT_COLUMN = "attempt_count"
        internal const val OUTBOX_LAST_ERROR_COLUMN = "last_error"
    }

    override val insertColumns = listOf(
        ID_COLUMN,

        WORKFLOW_ID_COLUMN,
        WORKFLOW_NAME_COLUMN,
        WORKFLOW_VERSION_COLUMN,
        WORKFLOW_POSITION_COLUMN,
        WORKFLOW_STATE_COLUMN,

        OUTBOX_STATUS_COLUMN,
        OUTBOX_SCHEDULED_FOR_COLUMN,
        OUTBOX_DELAYED_UNTIL_COLUMN,
        OUTBOX_ATTEMPT_COUNT_COLUMN,
        OUTBOX_LAST_ERROR_COLUMN
    )

    override val updateColumns = listOf(
        OUTBOX_STATUS_COLUMN,
        OUTBOX_SCHEDULED_FOR_COLUMN,
        OUTBOX_DELAYED_UNTIL_COLUMN,
        OUTBOX_ATTEMPT_COUNT_COLUMN,
        OUTBOX_LAST_ERROR_COLUMN
    )

    override val keyColumns: List<String> = listOf(ID_COLUMN)

    // MUST be in the same order as insertColumns
    override fun bindInsertWith(stmt: PreparedStatement, entity: T): PreparedStatement = stmt.apply {
        setString(1, entity.id)

        setString(2, entity.workflowId)
        setString(3, entity.workflowName)
        setString(4, entity.workflowVersion)
        setString(5, entity.workflowPosition)
        setString(6, entity.workflowState)

        setString(7, entity.outBoxStatus.name)
        setTimestamp(8, entity.outboxScheduledFor?.let { java.sql.Timestamp.from(it.toJavaInstant()) })
        setTimestamp(9, entity.outboxDelayedUntil?.let { java.sql.Timestamp.from(it.toJavaInstant()) })
        setInt(10, entity.outboxAttemptCount)
        setString(11, entity.outboxLastError)
    }

    // MUST be in the same order as updateColumns
    override fun bindUpdateWith(stmt: PreparedStatement, entity: T) = stmt.apply {
        setString(1, entity.outBoxStatus.name)
        setTimestamp(2, entity.outboxScheduledFor?.let { java.sql.Timestamp.from(it.toJavaInstant()) })
        setTimestamp(3, entity.outboxDelayedUntil?.let { java.sql.Timestamp.from(it.toJavaInstant()) })
        setInt(4, entity.outboxAttemptCount)
        setString(5, entity.outboxLastError)
    }

    // MUST be in the same order as KeyColumns
    override fun bindDeleteWith(stmt: PreparedStatement, entity: T) = stmt.apply {
        setString(1, entity.id) // Bind id to the first parameter
    }

    /**
     * Finds and locks messages that are ready to be processed.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param limit Maximum number of messages to retrieve
     * @param maxAttempts Maximum number of retry attempts allowed
     * @return List of locked messages ready for processing
     */
    suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection? = null): List<T> =
        withConnection(connection) {
            it.prepareStatement(findEntitiesToProcessSQL).use { stmt ->
                stmt.apply {
                    setTimestamp(1, java.sql.Timestamp.from(Clock.System.now().toJavaInstant()))
                    setInt(2, maxAttempts)
                    setInt(3, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findEntitiesToProcessSQL = """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = $PENDING
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            AND $OUTBOX_ATTEMPT_COUNT_COLUMN < ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    /**
     * Finds and locks messages that are ready to be deleted.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param cutoffDate Messages older than this date will be selected
     * @param limit Maximum number of messages to retrieve
     * @return List of locked messages ready for deletion
     */
    suspend fun findEntitiesToDelete(cutoffDate: Instant, limit: Int, connection: Connection? = null): List<T> =
        withConnection(connection) {
            it.prepareStatement(findEntitiesToDeleteSQL).use { stmt ->
                stmt.apply {
                    setTimestamp(1, java.sql.Timestamp.from(cutoffDate.toJavaInstant()))
                    setInt(2, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }

    /**
     * Retrieves an entity by its ID.
     *
     * @return The entity with the specified ID, or null if not found.
     */
    suspend fun findById(id: String, connection: Connection? = null): T? = withConnection(connection) { conn ->
        conn.prepareStatement(findByIdSql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByIdSql by lazy { "SELECT * FROM $tableName WHERE $ID_COLUMN = ? LIMIT 1" }

    private val findEntitiesToDeleteSQL = """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = $SENT
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    private fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}
