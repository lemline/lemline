// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CompletionOps.COMPLETED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.UPDATED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.with.WithIdRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

const val FORK_BRANCH_TABLE = "lemline_fork_branches"

/**
 * Repository for managing fork branch execution state.
 * Uses composition pattern with column bindings.
 *
 * @see ForkBranchModel for the entity model
 */
@ExperimentalTime
@ApplicationScoped
class ForkBranchRepository : CrudRepository<ForkBranchModel>(),
    WithIdRepository<ForkBranchModel> {

    companion object {
        internal const val FORK_ID_COLUMN = "fork_id"
        internal const val BRANCH_POSITION_COLUMN = "branch_position"
        internal const val BRANCH_OUTPUT_COLUMN = "branch_output"

        internal const val FAILED_AT_COLUMN = "failed_at"
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = FORK_BRANCH_TABLE

    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }

    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    override val columns: ColumnBindings<ForkBranchModel> by lazy {
        ColumnBindingsBuilder<ForkBranchModel>().apply {
            idColumn(idHelper)

            column(FORK_ID_COLUMN) { stmt, entity, idx -> setIDV7(stmt, idx, entity.forkId) }
            column(BRANCH_POSITION_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.branchPosition) }

            // Other columns
            column(BRANCH_OUTPUT_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.branchOutput) }
            column(COMPLETED_AT_COLUMN) { stmt, entity, idx ->
                entity.completedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column(FAILED_AT_COLUMN) { stmt, entity, idx ->
                entity.failedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column(ERROR_REASON_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.errorReason) }
            column(ERROR_CLASS_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.errorClass) }
            column(ERROR_MESSAGE_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.errorMessage) }
            column(ERROR_STACKTRACE_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.errorStackTrace) }
            // Override UPDATED_AT_COLUMN since fork_branches has NOT NULL constraint
            column(UPDATED_AT_COLUMN) { stmt, _, idx ->
                stmt.setTimestamp(
                    idx,
                    Timestamp.from(java.time.Instant.now())
                )
            }
        }.build()
    }

    override fun createModel(rs: ResultSet) = ForkBranchModel(
        forkId = getIDV7(rs, FORK_ID_COLUMN)!!,
        branchPosition = rs.getString(BRANCH_POSITION_COLUMN),
        id = getIDV7(rs, ID_COLUMN)!!,
        branchOutput = rs.getString(BRANCH_OUTPUT_COLUMN),
        completedAt = rs.getInstant(COMPLETED_AT_COLUMN),
        failedAt = rs.getInstant(FAILED_AT_COLUMN),
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN),
    )

    /**
     * Find all branches for a given fork ID, ordered by name.
     */
    suspend fun findByForkId(forkId: IDV7, connection: Connection? = null): List<ForkBranchModel> =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(findByForkIdSql).use { stmt ->
                setIDV7(stmt, 1, forkId)
                stmt.executeQuery().use { rs -> rs.toModels() }
            }
        }

    private val findByForkIdSql by lazy {
        "SELECT * FROM $tableName WHERE $FORK_ID_COLUMN = ? ORDER BY $BRANCH_POSITION_COLUMN"
    }

    /**
     * Delete all branches for a given fork ID.
     */
    suspend fun deleteByForkId(forkId: IDV7, connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(deleteByForkIdSql).use { stmt ->
                setIDV7(stmt, 1, forkId)
                stmt.executeUpdate()
            }
        }

    private val deleteByForkIdSql by lazy {
        "DELETE FROM $tableName WHERE $FORK_ID_COLUMN = ?"
    }
}
