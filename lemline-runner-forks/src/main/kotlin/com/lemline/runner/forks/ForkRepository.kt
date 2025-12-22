// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.helpers.completionColumns
import com.lemline.runner.common.repositories.helpers.readCompletionField
import com.lemline.runner.common.repositories.ops.CleanerRepository
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.InstanceRepository
import com.lemline.runner.common.repositories.ops.WORKFLOW_ID_COLUMN
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstanceMessage
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.ops.instanceColumns
import com.lemline.runner.common.repositories.ops.readCleanupField
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import com.lemline.runner.common.repositories.with.WithInstanceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val FORK_TABLE = "lemline_forks"

/**
 * Repository for managing fork execution state.
 * Uses composition to provide cleaner and instance operations.
 *
 * @see ForkModel for the entity model
 * @see ForkBranchRepository for branch operations
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ForkRepository : CrudRepository<ForkModel>(),
    WithIdRepository<ForkModel>,
    WithInstanceRepository<ForkModel>,
    WithCleanerRepository<ForkModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var forkBranchRepository: ForkBranchRepository

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    // Delegate WithInstanceRepository methods
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        instanceRepository.findByWorkflowId(workflowId, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    private val log = logger()

    companion object {
        internal const val FORK_POSITION_COLUMN = "position"
        internal const val FORK_COMPETE_COLUMN = "compete"
        internal const val FORK_OUTPUT_COLUMN = "output"
        internal const val FORK_FAILED_AT_COLUMN = "failed_at"
        internal const val FORK_ERROR_REASON_COLUMN = "error_reason"
        internal const val FORK_ERROR_CLASS_COLUMN = "error_class"
        internal const val FORK_ERROR_MESSAGE_COLUMN = "error_message"
        internal const val FORK_ERROR_STACK_TRACE_COLUMN = "error_stacktrace"
    }

    override val tableName = FORK_TABLE

    override val columns: ColumnBindings<ForkModel> by lazy {
        ColumnBindingsBuilder<ForkModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            completionColumns()
            cleanupColumns()

            // Fork-specific columns
            column(FORK_POSITION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.position)
            }
            column(FORK_COMPETE_COLUMN) { stmt, entity, idx ->
                stmt.setBoolean(idx, entity.compete)
            }
            column(FORK_OUTPUT_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.output)
            }
            column(FORK_FAILED_AT_COLUMN) { stmt, entity, idx ->
                entity.failedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
            }
            column(FORK_ERROR_REASON_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorReason)
            }
            column(FORK_ERROR_CLASS_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorClass)
            }
            column(FORK_ERROR_MESSAGE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorMessage)
            }
            column(FORK_ERROR_STACK_TRACE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorStackTrace)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet) = ForkModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ForkStarted>(idHelper)!!,
        position = rs.getString(FORK_POSITION_COLUMN),
        compete = rs.getBoolean(FORK_COMPETE_COLUMN),
        output = rs.getString(FORK_OUTPUT_COLUMN),
        failedAt = rs.getInstant(FORK_FAILED_AT_COLUMN),
        errorReason = rs.getString(FORK_ERROR_REASON_COLUMN),
        errorClass = rs.getString(FORK_ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(FORK_ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(FORK_ERROR_STACK_TRACE_COLUMN),
    )
        .readCompletionField(rs)
        .readCleanupField(rs)

    /**
     * Insert fork with all branches atomically.
     * Uses idempotent inserts (ON CONFLICT DO NOTHING / INSERT IGNORE) to handle message replays.
     *
     * @return The number of rows inserted for the fork model (0 if already exists, 1 if new)
     */
    suspend fun insertForkWithBranches(
        fork: ForkModel,
        branches: List<ForkBranchModel>
    ): Int = databaseConfig.withTransaction { conn ->
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
    ): ForkModel? = databaseConfig.withConnection(connection) { conn ->
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
     * processing branch completions with stale data.
     */
    suspend fun findByWorkflowIdAndPositionWithBranches(
        workflowId: WorkflowId,
        forkPosition: NodePosition,
        connection: Connection? = null
    ): Pair<ForkModel, List<ForkBranchModel>>? = databaseConfig.withConnection(connection) { conn ->
        // 1. Find and lock the fork
        val fork = findByWorkflowIdAndPositionForUpdate(workflowId, forkPosition, conn)
            ?: return@withConnection null

        // 2. Find all branches for this fork
        val branches = forkBranchRepository.findByForkId(fork.id, conn)

        Pair(fork, branches)
    }

    /**
     * Find fork by workflow ID and position with pessimistic locking.
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
