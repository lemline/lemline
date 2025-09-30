// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.nodes.NodePosition
import com.lemline.runner.models.ForkModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.OptionalCleanerRepository
import com.lemline.runner.repositories.capabilities.IdCapabilities
import com.lemline.runner.repositories.capabilities.InfoCapabilities
import com.lemline.runner.repositories.capabilities.InfoCapable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

const val FORK_TABLE = "lemline_forks"

@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkRepository : OptionalCleanerRepository<ForkModel>(),
    IdCapabilities<ForkModel>,
    InfoCapabilities<ForkModel> {
    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = FORK_TABLE

    companion object {
        internal const val FORK_ID_COLUMN = "fork_id"
        internal const val FORK_POSITION_COLUMN = "fork_position"
        internal const val FORK_NAME_COLUMN = "fork_name"
        internal const val FORK_OUTPUT_COLUMN = "fork_output"
    }

    private val infoCapable by lazy { InfoCapable(this) }

    private val forkMapping: Map<String, (PreparedStatement, ForkModel, Int) -> Unit> by lazy {
        mapOf(
            FORK_ID_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                setIDV7(stmt, idx, entity.forkId)
            },
            FORK_POSITION_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.forkPosition.toString())
            },
            FORK_NAME_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.forkName)
            },
            FORK_OUTPUT_COLUMN to { stmt: PreparedStatement, entity: ForkModel, idx: Int ->
                stmt.setString(idx, entity.forkOutput)
            }
        )
    }

    override val prepareStatementMap = super.prepareStatementMap + infoCapable.mapping + forkMapping


    private val ResultSet.workflowInfo get() = with(infoCapable) { this@workflowInfo.workflowInfo }


    override fun createModel(rs: ResultSet) = ForkModel(
        id = rs.id,
        workflowInfo = rs.workflowInfo,
        forkId = getIDV7(rs, FORK_ID_COLUMN)!!,
        forkPosition = NodePosition.from(rs.getString(FORK_POSITION_COLUMN)),
        forkName = rs.getString(FORK_NAME_COLUMN),
        forkOutput = rs.getString(FORK_OUTPUT_COLUMN),
        runStatus = rs.runStatus,
        runAt = rs.runAt,
    )

    suspend fun findByForkId(id: IDV7, connection: Connection?): List<ForkModel> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByForkIdSql).use { stmt ->
                setIDV7(stmt, 1, id)
                stmt.executeQuery().use {
                    it.toModels()
                }
            }
        }

    private val findByForkIdSql by lazy { "SELECT * FROM $tableName WHERE $FORK_ID_COLUMN = ?" }

    // ID Operations
    override suspend fun findById(id: IDV7, connection: Connection?): ForkModel? =
        idCapable.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idCapable.deleteById(id, connection)

    // Info Operations
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        infoCapable.findByWorkflowId(workflowId, connection)

    // Cleaner Operations
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, limit: Int, connection: Connection?) =
        cleanerCapable.findEntitiesToDelete(cutoffDate, limit, connection)

}
