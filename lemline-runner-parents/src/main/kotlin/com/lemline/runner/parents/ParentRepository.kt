// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.values.IDV7
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
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstanceMessage
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

const val PARENT_TABLE = "lemline_parents"

/**
 * Repository for managing parent workflow waiting state.
 * Stores parent workflow state while waiting for child workflow completion.
 * Uses composition to provide cleaner and instance operations.
 *
 * @see ParentModel for the state model
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ParentRepository : CrudRepository<ParentModel>(),
    WithIdRepository<ParentModel>,
    WithInstanceRepository<ParentModel>,
    WithCleanerRepository<ParentModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig
    
    override val tableName = PARENT_TABLE

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseConfig) }

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

    companion object {
        const val CHILD_ID_COLUMN = "child_id"
    }

    override val columns: ColumnBindings<ParentModel> by lazy {
        ColumnBindingsBuilder<ParentModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            completionColumns()
            cleanupColumns()

            // Parent-specific column
            column(CHILD_ID_COLUMN) { stmt, entity, idx ->
                setIDV7(stmt, idx, entity.childId.value)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet) = ParentModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.RunWorkflowStarted>(idHelper)!!,
        childId = WorkflowId(getIDV7(rs, CHILD_ID_COLUMN)!!),
    )
        .readCompletionField(rs)
        .readCleanupField(rs)

    /**
     * Finds a parent by the child workflow ID.
     * Used when a child workflow completes to resume its parent.
     *
     * @param childId The workflow ID of the child
     * @param connection An optional database connection to use
     * @return The parent model, or null if not found
     */
    suspend fun findByChildId(childId: WorkflowId, connection: Connection? = null): ParentModel? =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(findByChildIdSql).use { stmt ->
                setIDV7(stmt, 1, childId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByChildIdSql by lazy { "SELECT * FROM $tableName WHERE $CHILD_ID_COLUMN = ? LIMIT 1" }

}
