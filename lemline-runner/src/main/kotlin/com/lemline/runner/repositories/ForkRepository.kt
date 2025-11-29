// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.NodePosition
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val FORK_TABLE = "lemline_forks"

/**
 * Repository for managing fork execution state.
 * Extends [CleanerRepository] to follow standard pattern for waiting entities with cleanup tracking.
 *
 * Uses [ForkBranchRepository] for branch operations, ensuring consistent idempotent inserts.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ForkRepository : CleanerRepository<ForkModel>() {

    private val log = logger()

    companion object Companion {
        // Fork table columns
        internal const val FORK_POSITION_COLUMN = "position"
        internal const val FORK_COMPETE_COLUMN = "compete"
        internal const val FORK_OUTPUT_COLUMN = "output"
        internal const val FORK_FAILED_AT_COLUMN = "failed_at"
        internal const val FORK_ERROR_REASON_COLUMN = "error_reason"
        internal const val FORK_ERROR_CLASS_COLUMN = "error_class"
        internal const val FORK_ERROR_MESSAGE_COLUMN = "error_message"
        internal const val FORK_ERROR_STACK_TRACE_COLUMN = "error_stack_trace"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    lateinit var forkBranchRepository: ForkBranchRepository

    override val tableName = FORK_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, ForkModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            FORK_POSITION_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.position)
            },
            FORK_COMPETE_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setBoolean(idx, entity.compete)
            },
            FORK_OUTPUT_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.output)
            },
            FORK_FAILED_AT_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                entity.failedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
            },
            FORK_ERROR_REASON_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.errorReason)
            },
            FORK_ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.errorClass)
            },
            FORK_ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.errorMessage)
            },
            FORK_ERROR_STACK_TRACE_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.errorStackTrace)
            },
        )
    }

    override fun createModel(rs: ResultSet) = ForkModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ForkStarted>()!!,
        position = rs.getString(FORK_POSITION_COLUMN),
        compete = rs.getBoolean(FORK_COMPETE_COLUMN),
        output = rs.getString(FORK_OUTPUT_COLUMN),
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN),
        failedAt = rs.getInstant(FORK_FAILED_AT_COLUMN),
        errorReason = rs.getString(FORK_ERROR_REASON_COLUMN),
        errorClass = rs.getString(FORK_ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(FORK_ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(FORK_ERROR_STACK_TRACE_COLUMN),
    )

    /**
     * Insert fork with all branches atomically.
     * Uses idempotent inserts (ON CONFLICT DO NOTHING / INSERT IGNORE) to handle message replays.
     *
     * @return The number of rows inserted for the fork model (0 if already exists, 1 if new)
     */
    suspend fun insertForkWithBranches(
        fork: ForkModel,
        branches: List<ForkBranchModel>
    ): Int = withTransaction { conn ->
        // 1. Insert fork metadata (idempotent via base Repository)
        val forkRowsInserted = insert(fork, conn)

        // 2. Batch insert all branches (idempotent via ForkBranchRepository)
        if (branches.isNotEmpty()) {
            forkBranchRepository.insert(branches, conn)
        }

        log.debug { "Inserted fork ${fork.id} at ${fork.position} with ${branches.size} branches" }

        forkRowsInserted
    }

    /**
     * Find fork by workflow ID and position.
     */
    suspend fun findByWorkflowIdAndPosition(
        workflowId: WorkflowId,
        forkPosition: NodePosition,
        connection: Connection? = null
    ): ForkModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findByWorkflowIdAndPositionSql).use { stmt ->
            setIDV7(stmt, 1, workflowId.value)
            stmt.setString(2, forkPosition.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByWorkflowIdAndPositionSql by lazy {
        "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ? AND $FORK_POSITION_COLUMN = ? LIMIT 1"
    }

    /**
     * Update a fork branch (typically to mark it as completed or failed).
     * Delegates to [ForkBranchRepository.update].
     */
    suspend fun updateBranch(branch: ForkBranchModel, connection: Connection? = null): Int =
        forkBranchRepository.update(branch, connection)

    /**
     * Find fork with all its branches by workflow ID and position.
     *
     * Uses FOR UPDATE to acquire a row-level lock on the fork, preventing concurrent workers from
     * processing branch completions with stale data. This ensures thread-safe fork completion logic
     * when multiple branches complete simultaneously.
     *
     * Implementation uses two simple queries within the same connection/transaction:
     * 1. Find and lock the fork (SELECT ... FOR UPDATE)
     * 2. Find all branches for that fork (SELECT ... WHERE fork_id = ?)
     *
     * This approach is simpler and more portable across databases than using a JOIN with FOR UPDATE.
     */
    suspend fun findByWorkflowIdAndPositionWithBranches(
        workflowId: WorkflowId,
        forkPosition: NodePosition,
        connection: Connection? = null
    ): Pair<ForkModel, List<ForkBranchModel>>? = withConnection(connection) { conn ->
        // 1. Find and lock the fork
        val fork = findByWorkflowIdAndPositionForUpdate(workflowId, forkPosition, conn)
            ?: return@withConnection null

        // 2. Find all branches for this fork
        val branches = forkBranchRepository.findByForkId(fork.id, conn)

        Pair(fork, branches)
    }

    /**
     * Find fork by workflow ID and position with pessimistic locking.
     * Acquires a row-level lock using FOR UPDATE to prevent concurrent modifications.
     */
    private suspend fun findByWorkflowIdAndPositionForUpdate(
        workflowId: WorkflowId,
        forkPosition: NodePosition,
        connection: Connection
    ): ForkModel? {
        return connection.prepareStatement(findByWorkflowIdAndPositionForUpdateSql).use { stmt ->
            setIDV7(stmt, 1, workflowId.value)
            stmt.setString(2, forkPosition.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByWorkflowIdAndPositionForUpdateSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $WORKFLOW_ID_COLUMN = ? AND $FORK_POSITION_COLUMN = ?
        FOR UPDATE
        """.trimIndent()
    }
}
