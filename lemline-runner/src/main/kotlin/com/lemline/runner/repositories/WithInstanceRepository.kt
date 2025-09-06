// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowState
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.WithInstance
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*
import kotlin.time.ExperimentalTime

/**
 * Abstract repository class for managing entities that include workflow instance details.
 *
 * This class extends the functionality of `WithIdRepository` to provide additional operations
 * and mappings for entities that implement the `WithInstance` interface. It is intended for use in
 * scenarios where entities need to store and retrieve workflow-related metadata such as workflow ID,
 * name, version, position, state, and parent ID.
 *
 * Key Features:
 * - Defines SQL column mappings for workflow-related fields.
 * - Provides methods for retrieving entities based on workflow ID or parent ID.
 * - Maps instance-specific fields using prepared SQL statements.
 *
 * Generic Type:
 * - `T`: A type parameter extending `WithInstance`, representing the type of entity this repository will handle.
 *
 * Prepared Statement Map:
 * - Automatically maps entity properties to SQL columns for prepared statements.
 *
 * Methods:
 * - `findWithWorkflowId`: Retrieves a list of entities with the specified workflow ID.
 * - `findWithParentId`: Retrieves a list of entities with the specified parent ID.
 *
 * Utilities:
 * - `.getInstanceMessage`: Converts a SQL `ResultSet` into an `InstanceMessage` object.
 * - `.toModels`: Maps the entire `ResultSet` to a list of entities of type `T`.
 *
 * This class is marked with `@ExperimentalTime` to indicate that it makes use of experimental Kotlin time-related APIs.
 */
@Suppress("unused")
@ExperimentalTime
abstract class WithInstanceRepository<T : WithInstance> : WithIdRepository<T>() {

    companion object {
        internal const val WORKFLOW_ID_COLUMN = "workflow_id"
        internal const val WORKFLOW_NAME_COLUMN = "workflow_name"
        internal const val WORKFLOW_VERSION_COLUMN = "workflow_version"
        internal const val WORKFLOW_POSITION_COLUMN = "workflow_position"
        internal const val WORKFLOW_STATE_COLUMN = "workflow_state"
        internal const val PARENT_ID_COLUMN = "parent_id"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            WORKFLOW_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setUuid(stmt, idx, entity.workflowState?.workflowId?.value)
            },
            WORKFLOW_NAME_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.workflowName?.toString())
            },
            WORKFLOW_VERSION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.workflowVersion?.toString())
            },
            WORKFLOW_POSITION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.currentPosition?.toString())
            },
            WORKFLOW_STATE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.currentStates?.toJsonString())
            },
            PARENT_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setUuid(stmt, idx, entity.parentId?.value)
            }
        )
    }

    protected fun ResultSet.getInstanceMessage(): InstanceMessage? = when (val id = getUuid(this, WORKFLOW_ID_COLUMN)) {
        null -> null
        else -> InstanceMessage(
            workflowState = WorkflowState(
                workflowId = WorkflowId(id),
                workflowName = WorkflowName(getString(WORKFLOW_NAME_COLUMN)),
                workflowVersion = WorkflowVersion(getString(WORKFLOW_VERSION_COLUMN)),
                currentPosition = NodePosition.fromJsonString(getString(WORKFLOW_POSITION_COLUMN)),
                currentStates = NodeStates.fromJsonString(getString(WORKFLOW_STATE_COLUMN)),
            ),
            parentId = getUuid(this, PARENT_ID_COLUMN)?.let { IDV7(it) },
        )
    }

    /**
     * Retrieves an entity by its WorkflowId.
     *
     * @return The entity with the specified WorkflowId, or null if not found.
     */
    suspend fun findWithWorkflowId(workflowId: WorkflowId, connection: Connection? = null): List<T> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findWithWorkflowIdSql).use { stmt ->
                setUuid(stmt, 1, workflowId.value)
                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findWithWorkflowIdSql by lazy { "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ?" }


    /**
     * Retrieves an entity by its ParenId.
     *
     * @return The entity with the specified WorkflowId, or null if not found.
     */
    suspend fun findWithParentId(workflowId: UUID, connection: Connection? = null): List<T> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findWithParentIdSql).use { stmt ->
                setUuid(stmt, 1, workflowId)
                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findWithParentIdSql by lazy { "SELECT * FROM $tableName WHERE $PARENT_ID_COLUMN = ?" }

    protected fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}
