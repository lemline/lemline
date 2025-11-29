// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ForkBranchModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

const val FORK_BRANCH_TABLE = "lemline_fork_branches"

/**
 * Repository for managing fork branch execution state.
 * Uses composite primary key (fork_id, name) for branch identity.
 *
 * IDV7 helpers are inherited from [Repository].
 */
@ExperimentalTime
@ApplicationScoped
class ForkBranchRepository : Repository<ForkBranchModel>() {

    companion object {
        internal const val FORK_ID_COLUMN = "fork_id"
        internal const val NAME_COLUMN = "name"
        internal const val OUTPUT_COLUMN = "output"
        internal const val COMPLETED_AT_COLUMN = "completed_at"
        internal const val FAILED_AT_COLUMN = "failed_at"
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACK_TRACE_COLUMN = "error_stack_trace"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = FORK_BRANCH_TABLE

    override val keyColumns: List<String> = listOf(FORK_ID_COLUMN, NAME_COLUMN)

    override val prepareStatementMap: Map<String, (PreparedStatement, ForkBranchModel, Int) -> Unit> by lazy {
        mapOf(
            FORK_ID_COLUMN to { stmt, entity, idx -> setIDV7(stmt, idx, entity.forkId) },
            NAME_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.name) },
            OUTPUT_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.output) },
            COMPLETED_AT_COLUMN to { stmt, entity, idx ->
                entity.completedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            },
            FAILED_AT_COLUMN to { stmt, entity, idx ->
                entity.failedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, Types.TIMESTAMP)
            },
            ERROR_REASON_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.errorReason) },
            ERROR_CLASS_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.errorClass) },
            ERROR_MESSAGE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.errorMessage) },
            ERROR_STACK_TRACE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.errorStackTrace) },
            // Override UPDATED_AT_COLUMN since fork_branches has NOT NULL constraint
            UPDATED_AT_COLUMN to { stmt, _, idx -> stmt.setTimestamp(idx, Timestamp.from(java.time.Instant.now())) },
        )
    }

    override fun createModel(rs: ResultSet) = ForkBranchModel(
        forkId = getIDV7(rs, FORK_ID_COLUMN)!!,
        name = rs.getString(NAME_COLUMN),
        output = rs.getString(OUTPUT_COLUMN),
        completedAt = rs.getInstant(COMPLETED_AT_COLUMN),
        failedAt = rs.getInstant(FAILED_AT_COLUMN),
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACK_TRACE_COLUMN),
    )

    /**
     * Find all branches for a given fork ID, ordered by name.
     */
    suspend fun findByForkId(forkId: IDV7, connection: Connection? = null): List<ForkBranchModel> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByForkIdSql).use { stmt ->
                setIDV7(stmt, 1, forkId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(createModel(rs))
                        }
                    }
                }
            }
        }

    private val findByForkIdSql by lazy {
        "SELECT * FROM $tableName WHERE $FORK_ID_COLUMN = ? ORDER BY $NAME_COLUMN"
    }

    /**
     * Delete all branches for a given fork ID.
     */
    suspend fun deleteByForkId(forkId: IDV7, connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(deleteByForkIdSql).use { stmt ->
                setIDV7(stmt, 1, forkId)
                stmt.executeUpdate()
            }
        }

    private val deleteByForkIdSql by lazy {
        "DELETE FROM $tableName WHERE $FORK_ID_COLUMN = ?"
    }
}
