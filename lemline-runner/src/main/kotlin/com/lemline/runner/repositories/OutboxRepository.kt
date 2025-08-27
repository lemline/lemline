// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.*
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
 * @see com.lemline.runner.outbox.OutboxProcessor for the processing logic
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
        internal const val PARENT_ID_COLUMN = "parent_id"

        internal const val OUTBOX_STATUS_COLUMN = "outbox_status"
        internal const val OUTBOX_SCHEDULED_FOR_COLUMN = "outbox_scheduled_for"
        internal const val OUTBOX_DELAYED_UNTIL_COLUMN = "outbox_delayed_until"
        internal const val OUTBOX_ATTEMPT_COUNT_COLUMN = "outbox_attempt_count"
        internal const val OUTBOX_LAST_ERROR_COLUMN = "outbox_last_error"
    }

    protected val setUuid by lazy {
        when (databaseManager.dbType) {
            DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { stmt: PreparedStatement, parameterIndex: Int, uuid: UUID? ->
                when (uuid) {
                    null -> stmt.setNull(parameterIndex, Types.OTHER)
                    else -> stmt.setObject(parameterIndex, uuid)
                }
            }

            DB_TYPE_MYSQL -> { stmt: PreparedStatement, parameterIndex: Int, uuid: UUID? ->
                when (uuid) {
                    null -> stmt.setNull(parameterIndex, Types.BINARY)
                    else -> stmt.setBytes(parameterIndex, uuid.toBytes())
                }
            }

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    protected val getUuid by lazy {
        when (databaseManager.dbType) {
            DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { rs: ResultSet, columnName: String ->
                rs.getObject(columnName, UUID::class.java)
            }

            DB_TYPE_MYSQL -> { rs: ResultSet, columnName: String ->
                val bytes = rs.getBytes(columnName)
                bytes?.toUUID()
            }

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    private fun ByteArray.toUUID(): UUID {
        require(this.size == 16) { "ByteArray must be exactly 16 bytes for UUID conversion, but was ${this.size}" }
        val bb = ByteBuffer.wrap(this)
        val mostSignificantBits = bb.long
        val leastSignificantBits = bb.long
        return UUID(mostSignificantBits, leastSignificantBits)
    }

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()


    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setUuid(stmt, idx, entity.id)
            },
            WORKFLOW_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setUuid(stmt, idx, entity.instance?.workflowId)
            },
            WORKFLOW_NAME_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.instance?.workflowName)
            },
            WORKFLOW_VERSION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.instance?.workflowVersion)
            },
            WORKFLOW_POSITION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.instance?.workflowPosition?.serialized)
            },
            WORKFLOW_STATE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.instance?.workflowState?.serialized)
            },
            PARENT_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setUuid(stmt, idx, entity.instance?.parentId)
            },
            OUTBOX_STATUS_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outBoxStatus.name)
            },
            OUTBOX_SCHEDULED_FOR_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setTimestamp(idx, entity.outboxScheduledFor?.toJavaInstant()?.let { Timestamp.from(it) })
            },
            OUTBOX_DELAYED_UNTIL_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setTimestamp(idx, entity.outboxDelayedUntil?.toJavaInstant()?.let { Timestamp.from(it) })
            },
            OUTBOX_ATTEMPT_COUNT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setInt(idx, entity.outboxAttemptCount)
            },
            OUTBOX_LAST_ERROR_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.outboxLastError)
            }
        )
    }

    override val keyColumns: List<String> = listOf(ID_COLUMN)

    protected fun ResultSet.getInstanceMessage() = InstanceMessage.fromStrings(
        workflowId = getUuid(this, WORKFLOW_ID_COLUMN),
        workflowName = getString(WORKFLOW_NAME_COLUMN),
        workflowVersion = getString(WORKFLOW_VERSION_COLUMN),
        workflowPosition = getString(WORKFLOW_POSITION_COLUMN),
        workflowState = getString(WORKFLOW_STATE_COLUMN),
        parentId = getUuid(this, PARENT_ID_COLUMN),
    )

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
                    setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
                    setInt(2, maxAttempts)
                    setInt(3, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findEntitiesToProcessSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = '$PENDING'
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            AND $OUTBOX_ATTEMPT_COUNT_COLUMN < ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

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
                    setTimestamp(1, Timestamp.from(cutoffDate.toJavaInstant()))
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
    suspend fun findById(id: UUID, connection: Connection? = null): T? = withConnection(connection) { conn ->
        conn.prepareStatement(findByIdSql).use { stmt ->
            setUuid(stmt, 1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByIdSql by lazy { "SELECT * FROM $tableName WHERE $ID_COLUMN = ? LIMIT 1" }

    /**
     * Retrieves an entity by its WorkflowId.
     *
     * @return The entity with the specified WorkflowId, or null if not found.
     */
    suspend fun findByWorkflowId(workflowId: UUID, connection: Connection? = null): T? =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByWorkflowIdSql).use { stmt ->
                setUuid(stmt, 1, workflowId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByWorkflowIdSql by lazy { "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ? LIMIT 1" }


    private val findEntitiesToDeleteSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $OUTBOX_STATUS_COLUMN = '$SENT'
            AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
            ORDER BY $OUTBOX_DELAYED_UNTIL_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    private fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}
