// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.capabilities

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.repositories.bases.Repository
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

internal const val WORKFLOW_ID_COLUMN = "workflow_id"
internal const val WORKFLOW_NAMESPACE_COLUMN = "workflow_namespace"
internal const val WORKFLOW_NAME_COLUMN = "workflow_name"
internal const val WORKFLOW_VERSION_COLUMN = "workflow_version"

/**
 * Capabilities of [InfoColumns] and [OptionalInfoColumns]
 */
interface InfoCapabilities<T : InfoColumnsBase> {
    /**
     * Retrieves a list of entities associated with the provided workflow identifier.
     *
     * @param workflowId The unique identifier of the workflow used to find the related entities.
     * @param connection An optional database connection to use for the query. If not provided, a new connection may be used.
     * @return A list of entities corresponding to the given workflow identifier. If no entities are found, an empty list is returned.
     */
    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): List<T>
}

/**
 * Represents a model with nullable workflow-related properties.
 */
interface OptionalInfoColumns : InfoColumnsBase

/**
 * Represents a model with definite workflow-related properties.
 */
interface InfoColumns : InfoColumnsBase {
    override val workflowId: WorkflowId
    override val workflowNamespace: WorkflowNamespace
    override val workflowName: WorkflowName
    override val workflowVersion: WorkflowVersion
}

interface InfoColumnsBase : IdColumn {
    val workflowId: WorkflowId?
    val workflowNamespace: WorkflowNamespace?
    val workflowName: WorkflowName?
    val workflowVersion: WorkflowVersion?
}

/**
 * Implementation of InfoCapable for InfoModelNullable
 */
class OptionalInfoCapable<T : OptionalInfoColumns>(
    private val repository: Repository<T>,
) : InfoCapableBase<T>(repository) {

    val ResultSet.workflowInfo: WorkflowInfo?
        get() = workflowId?.let { WorkflowInfo(it, workflowNamespace!!, workflowName!!, workflowVersion!!) }

    val ResultSet.workflowId: WorkflowId?
        get() = repository.getIDV7(this, WORKFLOW_ID_COLUMN)?.let { WorkflowId(it) }

    val ResultSet.workflowNamespace: WorkflowNamespace?
        get() = getString(WORKFLOW_NAMESPACE_COLUMN)?.let { WorkflowNamespace(it) }

    val ResultSet.workflowName: WorkflowName?
        get() = getString(WORKFLOW_NAME_COLUMN)?.let { WorkflowName(it) }

    val ResultSet.workflowVersion: WorkflowVersion?
        get() = getString(WORKFLOW_VERSION_COLUMN)?.let { WorkflowVersion(it) }
}

/**
 * Implementation of InfoCapable for InfoModel
 */
class InfoCapable<T : InfoColumns>(
    private val repository: Repository<T>,
) : InfoCapableBase<T>(repository) {

    val ResultSet.workflowInfo: WorkflowInfo
        get() = WorkflowInfo(workflowId, workflowNamespace, workflowName, workflowVersion)

    val ResultSet.workflowId: WorkflowId
        get() = WorkflowId(repository.getIDV7(this, WORKFLOW_ID_COLUMN)!!)

    val ResultSet.workflowNamespace: WorkflowNamespace
        get() = WorkflowNamespace(getString(WORKFLOW_NAMESPACE_COLUMN))

    val ResultSet.workflowName: WorkflowName
        get() = WorkflowName(getString(WORKFLOW_NAME_COLUMN))

    val ResultSet.workflowVersion: WorkflowVersion
        get() = WorkflowVersion(getString(WORKFLOW_VERSION_COLUMN))
}

abstract class InfoCapableBase<T : InfoColumnsBase>(
    private val repository: Repository<T>,
) : InfoCapabilities<T> {

    val mapping: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            WORKFLOW_ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                repository.setIDV7(stmt, idx, entity.workflowId?.value)
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
        )
    }

    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?): List<T> =
        repository.withConnection(connection) { conn ->
            conn.prepareStatement(findWithWorkflowIdSql).use { stmt ->
                repository.setIDV7(stmt, 1, workflowId.value)
                stmt.executeQuery().use {
                    with(repository) { it.toModels() }
                }
            }
        }

    private val findWithWorkflowIdSql by lazy { "SELECT * FROM ${repository.tableName} WHERE $WORKFLOW_ID_COLUMN = ?" }
}
