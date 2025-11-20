// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.nodes.NodePosition
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
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val FORK_TABLE = "lemline_forks"
const val FORK_BRANCH_TABLE = "lemline_fork_branches"

/**
 * Repository for managing fork execution state.
 * Extends [CleanerRepository] to follow standard pattern for waiting entities with cleanup tracking.
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
        internal const val FORK_FAILURE_ID_COLUMN = "failure_id"

        // Fork branch table columns
        internal const val BRANCH_FORK_ID_COLUMN = "fork_id"
        internal const val BRANCH_NAME_COLUMN = "name"
        internal const val BRANCH_OUTPUT_COLUMN = "output"
        internal const val BRANCH_COMPLETED_AT_COLUMN = "completed_at"
        internal const val BRANCH_FAILED_AT_COLUMN = "failed_at"
        internal const val BRANCH_FAILURE_ID_COLUMN = "failure_id"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

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
            FORK_FAILURE_ID_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                setIDV7(stmt, idx, entity.failureId)
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
        failureId = getIDV7(rs, FORK_FAILURE_ID_COLUMN),
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
            val insertBranchSql by lazy {
                """
                INSERT INTO $FORK_BRANCH_TABLE (
                    $BRANCH_FORK_ID_COLUMN, $BRANCH_NAME_COLUMN, $BRANCH_OUTPUT_COLUMN, $BRANCH_FAILURE_ID_COLUMN,
                    $BRANCH_COMPLETED_AT_COLUMN, $BRANCH_FAILED_AT_COLUMN, $CREATED_AT_COLUMN, $UPDATED_AT_COLUMN
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            }

            conn.prepareStatement(insertBranchSql).use { stmt ->
                branches.forEach { branch ->
                    setIDV7(stmt, 1, branch.forkId)
                    stmt.setString(2, branch.name)
                    stmt.setString(3, branch.output)
                    setIDV7(stmt, 4, branch.failureId)
                    stmt.setTimestamp(5, branch.completedAt?.toJavaInstant()?.let { Timestamp.from(it) })
                    stmt.setTimestamp(6, branch.failedAt?.toJavaInstant()?.let { Timestamp.from(it) })
                    stmt.setTimestamp(7, Timestamp.from(java.time.Instant.now()))
                    stmt.setNull(8, Types.TIMESTAMP)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        log.debug { "Inserted fork ${fork.id} at ${fork.position} with ${branches.size} branches" }
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
     * Update a fork branch (typically to mark it as completed).
     */
    suspend fun updateBranch(branch: ForkBranchModel, connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(updateBranchSql).use { stmt ->
                stmt.setString(1, branch.output)
                setIDV7(stmt, 2, branch.failureId)
                stmt.setTimestamp(3, branch.completedAt?.toJavaInstant()?.let { Timestamp.from(it) })
                stmt.setTimestamp(4, branch.failedAt?.toJavaInstant()?.let { Timestamp.from(it) })
                stmt.setTimestamp(5, Timestamp.from(java.time.Instant.now()))
                setIDV7(stmt, 6, branch.forkId)
                stmt.setString(7, branch.name)
                stmt.executeUpdate()
            }
        }

    private val updateBranchSql by lazy {
        """
        UPDATE $FORK_BRANCH_TABLE
        SET $BRANCH_OUTPUT_COLUMN = ?,
            $BRANCH_FAILURE_ID_COLUMN = ?,
            $BRANCH_COMPLETED_AT_COLUMN = ?,
            $BRANCH_FAILED_AT_COLUMN = ?,
            $UPDATED_AT_COLUMN = ?
        WHERE $BRANCH_FORK_ID_COLUMN = ? AND $BRANCH_NAME_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Find fork with all its branches by workflow ID and position.
     * More efficient than separate queries when you need both fork and branches.
     */
    suspend fun findByWorkflowIdAndPositionWithBranches(
        workflowId: WorkflowId,
        forkPosition: NodePosition,
        connection: Connection? = null
    ): Pair<ForkModel, List<ForkBranchModel>>? = withConnection(connection) { conn ->
        conn.prepareStatement(findByWorkflowIdAndPositionWithBranchesSql).use { stmt ->
            setIDV7(stmt, 1, workflowId.value)
            stmt.setString(2, forkPosition.toString())
            stmt.executeQuery().use { rs ->
                var fork: ForkModel? = null
                val branches = mutableListOf<ForkBranchModel>()

                while (rs.next()) {
                    // Fork data is the same in all rows, only create once
                    if (fork == null) {
                        fork = createModel(rs)
                    }

                    // Check if there's branch data (LEFT JOIN might have NULLs)
                    if (rs.getObject("b_$BRANCH_FORK_ID_COLUMN") != null) {
                        branches.add(rs.toForkBranchModelFromJoin())
                    }
                }

                fork?.let { Pair(it, branches) }
            }
        }
    }

    private val findByWorkflowIdAndPositionWithBranchesSql by lazy {
        """
        SELECT f.$ID_COLUMN,
               f.$WORKFLOW_ID_COLUMN,
               f.$WORKFLOW_NAMESPACE_COLUMN,
               f.$WORKFLOW_NAME_COLUMN,
               f.$WORKFLOW_VERSION_COLUMN,
               f.$WORKFLOW_POSITION_COLUMN,
               f.$WORKFLOW_STATE_COLUMN,
               f.$FORK_POSITION_COLUMN,
               f.$FORK_COMPETE_COLUMN,
               f.$FORK_OUTPUT_COLUMN,
               f.$FORK_FAILED_AT_COLUMN,
               f.$FORK_FAILURE_ID_COLUMN,
               f.$OUTBOX_COMPLETED_AT_COLUMN,
               f.$CREATED_AT_COLUMN,
               f.$UPDATED_AT_COLUMN,
               b.$BRANCH_FORK_ID_COLUMN AS b_$BRANCH_FORK_ID_COLUMN,
               b.$BRANCH_NAME_COLUMN AS b_$BRANCH_NAME_COLUMN,
               b.$BRANCH_OUTPUT_COLUMN AS b_$BRANCH_OUTPUT_COLUMN,
               b.$BRANCH_FAILURE_ID_COLUMN AS b_$BRANCH_FAILURE_ID_COLUMN,
               b.$BRANCH_COMPLETED_AT_COLUMN AS b_$BRANCH_COMPLETED_AT_COLUMN,
               b.$BRANCH_FAILED_AT_COLUMN AS b_$BRANCH_FAILED_AT_COLUMN,
               b.$CREATED_AT_COLUMN AS b_$CREATED_AT_COLUMN,
               b.$UPDATED_AT_COLUMN AS b_$UPDATED_AT_COLUMN
        FROM $tableName f
        LEFT JOIN $FORK_BRANCH_TABLE b ON f.$ID_COLUMN = b.$BRANCH_FORK_ID_COLUMN
        WHERE f.$WORKFLOW_ID_COLUMN = ? AND f.$FORK_POSITION_COLUMN = ?
        ORDER BY b.$BRANCH_NAME_COLUMN
        """.trimIndent()
    }

    // Helper method to convert ResultSet to ForkBranchModel (from join query with aliased columns)
    private fun ResultSet.toForkBranchModelFromJoin() = ForkBranchModel(
        forkId = getIDV7(this, "b_$BRANCH_FORK_ID_COLUMN")!!,
        name = getString("b_$BRANCH_NAME_COLUMN"),
        output = getString("b_$BRANCH_OUTPUT_COLUMN"),
        completedAt = getInstant("b_$BRANCH_COMPLETED_AT_COLUMN"),
        failedAt = getInstant("b_$BRANCH_FAILED_AT_COLUMN"),
        failureId = getIDV7(this, "b_$BRANCH_FAILURE_ID_COLUMN"),
    )
}
