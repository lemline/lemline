// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.InstanceModel
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
@ExperimentalSerializationApi
@Suppress("unused")
@ExperimentalTime
abstract class WithInstanceRepository<T : InstanceModel> : WithIdRepository<T>() {

    companion object {
        internal const val WORKFLOW_ID_COLUMN = "workflow_id"
        internal const val WORKFLOW_NAMESPACE_COLUMN = "workflow_namespace"
        internal const val WORKFLOW_NAME_COLUMN = "workflow_name"
        internal const val WORKFLOW_VERSION_COLUMN = "workflow_version"
        internal const val WORKFLOW_POSITION_COLUMN = "workflow_position"
        internal const val WORKFLOW_STATE_COLUMN = "workflow_state"
        internal const val PARENT_ID_COLUMN = "parent_id"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            WORKFLOW_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setIDV7(stmt, idx, entity.workflowId?.value)
            },
            WORKFLOW_NAMESPACE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowNamespace?.toString())
            },
            WORKFLOW_NAME_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowName?.toString())
            },
            WORKFLOW_VERSION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowVersion?.toString())
            },
            WORKFLOW_POSITION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.currentPosition?.toString())
            },
            WORKFLOW_STATE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.workflowState?.currentStates?.toJsonString())
            },
            PARENT_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                setIDV7(stmt, idx, entity.parentId)
            }
        )
    }

    protected fun ResultSet.getInstanceMessage(): InstanceMessage? = when (val id = getIDV7(this, WORKFLOW_ID_COLUMN)) {
        null -> null
        else -> InstanceMessage(
            workflowInfo = WorkflowInfo(
                workflowId = WorkflowId(id),
                workflowNamespace = WorkflowNamespace(getString(WORKFLOW_NAMESPACE_COLUMN)),
                workflowName = WorkflowName(getString(WORKFLOW_NAME_COLUMN)),
                workflowVersion = WorkflowVersion(getString(WORKFLOW_VERSION_COLUMN)),
            ),
            workflowState = WorkflowState(
                currentPosition = NodePosition.from(getString(WORKFLOW_POSITION_COLUMN)),
                currentStates = NodeStates.fromJsonString(getString(WORKFLOW_STATE_COLUMN)),
            ),
            parentId = getIDV7(this, PARENT_ID_COLUMN),
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
                setIDV7(stmt, 1, workflowId.value)
                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findWithWorkflowIdSql by lazy { "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ?" }


    /**
     * Retrieves an entity by its ParenId.
     *
     * @return The entity with the specified parentId, or null if not found.
     */
    suspend fun findWithParentId(parentId: IDV7, connection: Connection? = null): List<T> =
        withConnection(connection) { conn ->
            conn.prepareStatement(findWithParentIdSql).use { stmt ->
                setIDV7(stmt, 1, parentId)
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
