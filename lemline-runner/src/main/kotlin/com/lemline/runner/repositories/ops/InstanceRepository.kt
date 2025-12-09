// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.ops

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowState
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.InstanceModel
import com.lemline.runner.models.WithInstanceMessage
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.helpers.IdV7Helper
import com.lemline.runner.repositories.with.WithInstanceRepository
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val WORKFLOW_ID_COLUMN = "workflow_id"
const val WORKFLOW_NAMESPACE_COLUMN = "workflow_namespace"
const val WORKFLOW_NAME_COLUMN = "workflow_name"
const val WORKFLOW_VERSION_COLUMN = "workflow_version"
const val WORKFLOW_POSITION_COLUMN = "workflow_position"
const val WORKFLOW_STATE_COLUMN = "workflow_state"

/**
 * Helper class providing workflow instance-related database operations.
 * Use this via composition instead of inheriting from WithInstanceRepository.
 *
 * @param T The entity type, must implement [InstanceModel]
 * @param tableName The database table name
 * @param idHelper The IDV7 helper for database-agnostic ID handling
 * @param createModel Function to create a model from a ResultSet
 * @param databaseManager The database manager for connections
 */
class InstanceRepository<T>(
    private val tableName: String,
    private val idHelper: IdV7Helper,
    private val createModel: (ResultSet) -> T,
    private val databaseManager: DatabaseManager
) : WithInstanceRepository<T> {
    /**
     * Retrieves entities by WorkflowId.
     *
     * @param workflowId The workflow ID to search for
     * @param connection Optional database connection to use
     * @return List of entities with the specified WorkflowId
     */
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?): T? =
        databaseManager.withConnection(connection) { conn ->
            conn.prepareStatement(findByWorkflowIdSql).use { stmt ->
                idHelper.set(stmt, 1, workflowId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByWorkflowIdSql by lazy {
        "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ? LIMIT 1"
    }
}

/**
 * Extension function to deserialize InstanceMessage from ResultSet with type-safe WorkflowState.
 * Uses inline + reified to allow each caller to specify the exact state type it needs.
 *
 * @param idHelper The IDV7 helper for database-agnostic ID handling
 */
inline fun <reified S : WorkflowState> ResultSet.getInstanceMessage(idHelper: IdV7Helper): InstanceMessage<S>? =
    when (idHelper.get(this, WORKFLOW_ID_COLUMN)) {
        null -> null
        else -> InstanceMessage(
            workflowInfo = WorkflowInfo(
                namespace = WorkflowNamespace(getString(WORKFLOW_NAMESPACE_COLUMN)),
                name = WorkflowName(getString(WORKFLOW_NAME_COLUMN)),
                version = WorkflowVersion(getString(WORKFLOW_VERSION_COLUMN)),
            ),
            workflowState = WorkflowState.fromJsonString(getString(WORKFLOW_STATE_COLUMN)) as S,
        )
    }

/**
 * Extension function to add workflow instance columns to ColumnBindingsBuilder.
 * Adds all instance-related column bindings for entities implementing [WithInstanceMessage].
 *
 * Columns added:
 * - workflow_id (from workflowState.workflowId)
 * - workflow_namespace
 * - workflow_name
 * - workflow_version
 * - workflow_position (from workflowState.nodePosition)
 * - workflow_state (JSON serialized)
 *
 * @param idHelper The IDV7 helper for database-agnostic ID handling
 *
 * Usage:
 * ```kotlin
 * override val columns by lazy {
 *     ColumnBindingsBuilder<MyModel>().apply {
 *         key("id") { stmt, entity, idx -> setIDV7(stmt, idx, entity.id) }
 *         instanceColumns(idHelper)  // adds all 6 instance columns
 *         // ... other columns
 *     }.build()
 * }
 * ```
 */
fun <T : WithInstanceMessage> ColumnBindingsBuilder<T>.instanceColumns(idHelper: IdV7Helper) {
    column(WORKFLOW_ID_COLUMN) { stmt, entity, idx ->
        idHelper.set(stmt, idx, entity.workflowState.workflowId.value)
    }
    column(WORKFLOW_NAMESPACE_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.workflowNamespace.toString())
    }
    column(WORKFLOW_NAME_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.workflowName.toString())
    }
    column(WORKFLOW_VERSION_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.workflowVersion.toString())
    }
    column(WORKFLOW_POSITION_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.nodePosition.toString())
    }
    column(WORKFLOW_STATE_COLUMN) { stmt, entity, idx ->
        stmt.setString(idx, entity.workflowState.toJsonString())
    }
}
