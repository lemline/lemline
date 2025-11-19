// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.BranchStatus
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkCompletionResult
import com.lemline.runner.models.ForkModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

const val FORK_TABLE = "lemline_forks"
const val FORK_BRANCH_TABLE = "lemline_fork_branches"

/**
 * Repository for managing fork execution state.
 * Extends WaitingRepository to follow standard pattern for waiting entities with cleanup tracking.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ForkRepository : CleanerRepository<ForkModel>() {

    private val log = logger()

    companion object Companion {
        internal const val FORK_POSITION_COLUMN = "fork_position"
        internal const val COMPETE_COLUMN = "compete"
        internal const val BRANCH_COUNT_COLUMN = "branch_count"
        internal const val FORK_ID_COLUMN = "fork_id"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = FORK_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, ForkModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            FORK_POSITION_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.forkPosition)
            },
            COMPETE_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setBoolean(idx, entity.compete)
            },
            BRANCH_COUNT_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setInt(idx, entity.branchCount)
            }
        )
    }

    override fun createModel(rs: ResultSet) = ForkModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ForkStarted>()!!,
        forkPosition = rs.getString(FORK_POSITION_COLUMN),
        compete = rs.getBoolean(COMPETE_COLUMN),
        branchCount = rs.getInt(BRANCH_COUNT_COLUMN),
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN),
    )

    /**
     * Insert fork with all branches atomically.
     */
    suspend fun insertForkWithBranches(
        fork: ForkModel,
        branches: List<ForkBranchModel>
    ) = withTransaction { conn ->
        // 1. Insert fork metadata
        insert(fork, conn)

        // 2. Batch insert all branches
        if (branches.isNotEmpty()) {
            conn.prepareStatement(
                """
                INSERT INTO $FORK_BRANCH_TABLE (
                    fork_id, branch_index, branch_name, branch_node_position,
                    status, output, error, completed_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
            ).use { stmt ->
                branches.forEach { branch ->
                    setIDV7(stmt, 1, branch.forkId)
                    stmt.setInt(2, branch.branchIndex)
                    stmt.setString(3, branch.branchName)
                    stmt.setString(4, branch.branchNodePosition)
                    stmt.setString(5, branch.status.name)
                    stmt.setString(6, branch.output)
                    stmt.setString(7, branch.error)
                    stmt.setTimestamp(8, branch.completedAt?.toJavaInstant()?.let { Timestamp.from(it) })
                    stmt.setTimestamp(9, Timestamp.from(branch.createdAt.toJavaInstant()))
                    stmt.setTimestamp(10, Timestamp.from(branch.updatedAt.toJavaInstant()))
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        log.debug { "Inserted fork ${fork.id} at ${fork.forkPosition} with ${branches.size} branches" }
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
     * Record branch completion with pessimistic locking.
     */
    suspend fun recordBranchCompletion(
        forkId: IDV7,
        branchIndex: Int,
        branchOutput: JsonElement
    ): ForkCompletionResult = withTransaction { conn ->
        // STEP 1: Update branch row
        conn.prepareStatement(updateBranchCompletionSql).use { stmt ->
            stmt.setString(1, BranchStatus.COMPLETED.name)
            stmt.setString(2, LemlineJson.encodeToString(branchOutput))
            setIDV7(stmt, 3, forkId)
            stmt.setInt(4, branchIndex)
            stmt.executeUpdate()
        }

        // STEP 2: Lock fork row using FOR UPDATE
        val fork = conn.prepareStatement(lockForkForUpdateSql).use { stmt ->
            setIDV7(stmt, 1, forkId)
            stmt.executeQuery().use { rs ->
                require(rs.next()) { "Fork not found: $forkId" }
                createModel(rs)
            }
        }

        // STEP 3: Count completed branches
        val completedCount = conn.prepareStatement(countCompletedBranchesSql).use { stmt ->
            setIDV7(stmt, 1, forkId)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getInt("completed_count")
            }
        }

        val compete = fork.compete
        val branchCount = fork.branchCount

        val isComplete = if (compete) completedCount > 0 else completedCount == branchCount

        log.debug {
            "Fork $forkId: $completedCount/$branchCount branches completed, compete=$compete, isComplete=$isComplete"
        }

        val taskStates = fork.workflowState.taskStates
        val branches = getBranches(forkId, conn)

        ForkCompletionResult(
            isComplete = isComplete,
            completedCount = completedCount,
            branchCount = branchCount,
            compete = compete,
            taskStates = taskStates,
            branches = branches
        )
    }

    private val updateBranchCompletionSql by lazy {
        """
        UPDATE $FORK_BRANCH_TABLE
        SET status = ?,
            output = ?,
            completed_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE fork_id = ? AND branch_index = ?
        """.trimIndent()
    }

    private val lockForkForUpdateSql by lazy {
        "SELECT * FROM $tableName WHERE $ID_COLUMN = ? FOR UPDATE"
    }

    private val countCompletedBranchesSql by lazy {
        """
        SELECT COUNT(*) as completed_count
        FROM $FORK_BRANCH_TABLE
        WHERE fork_id = ? AND status = 'COMPLETED'
        """.trimIndent()
    }

    /**
     * Get all branches for a fork.
     */
    suspend fun getBranches(forkId: IDV7, connection: Connection? = null): List<ForkBranchModel> =
        withConnection(connection) { conn ->
            getBranches(forkId, conn)
        }

    private fun getBranches(forkId: IDV7, conn: Connection): List<ForkBranchModel> {
        return conn.prepareStatement(getBranchesSql).use { stmt ->
            setIDV7(stmt, 1, forkId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toForkBranchModel())
                    }
                }
            }
        }
    }

    private val getBranchesSql by lazy {
        "SELECT * FROM $FORK_BRANCH_TABLE WHERE fork_id = ? ORDER BY branch_index"
    }

    /**
     * Delete fork and all branches (CASCADE).
     */
    suspend fun delete(forkId: IDV7) = withTransaction { conn ->
        // Branches will be cascade deleted automatically
        conn.prepareStatement(deleteForkSql).use { stmt ->
            setIDV7(stmt, 1, forkId)
            stmt.executeUpdate()
        }

        log.debug { "Deleted fork $forkId" }
    }

    private val deleteForkSql by lazy {
        "DELETE FROM $tableName WHERE $ID_COLUMN = ?"
    }

    // Helper method to convert ResultSet to ForkBranchModel
    private fun ResultSet.toForkBranchModel() = ForkBranchModel(
        forkId = getIDV7(this, FORK_ID_COLUMN)!!,
        branchIndex = getInt("branch_index"),
        branchName = getString("branch_name"),
        branchNodePosition = getString("branch_node_position"),
        status = BranchStatus.valueOf(getString("status")),
        output = getString("output"),
        error = getString("error"),
        completedAt = getTimestamp("completed_at")?.toInstant()?.toKotlinInstant(),
        createdAt = getTimestamp("created_at").toInstant().toKotlinInstant(),
        updatedAt = getTimestamp("updated_at").toInstant().toKotlinInstant()
    )
}
