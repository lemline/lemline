@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.capabilities

import com.lemline.common.values.IDV7
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.NodeStates
import com.lemline.runner.repositories.bases.Repository
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

internal const val WORKFLOW_POSITION_COLUMN = "workflow_position"
internal const val WORKFLOW_STATE_COLUMN = "workflow_state"
internal const val PARENT_ID_COLUMN = "parent_id"

/**
 * Capabilities of [StateColumns] and [OptionalStateColumns]
 */
interface StateCapabilities<T : StateColumnsBase> {
    suspend fun findByParentId(parentId: IDV7, connection: Connection? = null): List<T>
}

/**
 * Represents a model with nullable workflow state properties.
 */
interface OptionalStateColumns : StateColumnsBase

/**
 * Represents a model with definite workflow state properties.
 */
interface StateColumns : StateColumnsBase {
    override val nodePosition: NodePosition
    override val nodeStates: NodeStates
}

interface StateColumnsBase {
    val nodePosition: NodePosition?
    val nodeStates: NodeStates?
    val parentId: IDV7?
}

/**
 * Implementation of capabilities for [StateColumns]
 */
class StateCapable<T : StateColumns>(
    repository: Repository<T>,
) : StateCapableBase<T>(repository) {

    val ResultSet.nodePosition: NodePosition
        get() = NodePosition.from(getString(WORKFLOW_POSITION_COLUMN))

    val ResultSet.nodeStates: NodeStates
        get() = NodeStates.fromJsonString(getString(WORKFLOW_STATE_COLUMN))
}

/**
 * Implementation of capabilities for [OptionalStateColumns]
 */
class OptionalStateCapable<T : OptionalStateColumns>(
    repository: Repository<T>,
) : StateCapableBase<T>(repository) {

    val ResultSet.nodePosition: NodePosition?
        get() = getString(WORKFLOW_POSITION_COLUMN)?.let { NodePosition.from(it) }

    val ResultSet.nodeStates: NodeStates?
        get() = getString(WORKFLOW_STATE_COLUMN)?.let { NodeStates.fromJsonString(it) }
}

abstract class StateCapableBase<T : StateColumnsBase>(
    private val repository: Repository<T>,
) : StateCapabilities<T> {

    val ResultSet.parentId: IDV7? get() = repository.getIDV7(this, PARENT_ID_COLUMN)

    val mapping: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            WORKFLOW_POSITION_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.nodePosition?.toString())
            },
            WORKFLOW_STATE_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                stmt.setString(idx, entity.nodeStates?.toJsonString())
            },
            PARENT_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                repository.setIDV7(stmt, idx, entity.parentId)
            }
        )
    }

    override suspend fun findByParentId(parentId: IDV7, connection: Connection?): List<T> =
        repository.withConnection(connection) { conn ->
            conn.prepareStatement(findWithParentIdSql).use { stmt ->
                repository.setIDV7(stmt, 1, parentId)
                stmt.executeQuery().use {
                    with(repository) { it.toModels() }
                }
            }
        }

    private val findWithParentIdSql by lazy { "SELECT * FROM ${repository.tableName} WHERE $PARENT_ID_COLUMN = ?" }
}
