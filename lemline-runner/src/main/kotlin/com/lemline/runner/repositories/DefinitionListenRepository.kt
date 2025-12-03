// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DefinitionListenModel
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val DEFINITION_LISTEN_TABLE = "lemline_definition_listens"

/**
 * Repository for managing listen task definitions extracted from workflow definitions.
 *
 * This repository stores listen task configurations that are extracted when
 * workflow definitions are registered. Each listen task has an associated set
 * of filters stored in [DefinitionListenFilterRepository].
 *
 * ## Usage
 *
 * When a workflow definition containing listen tasks is saved:
 * 1. Extract all listen tasks from the workflow
 * 2. Insert each listen task using this repository
 * 3. Insert filters for each listen task using [DefinitionListenFilterRepository]
 * 4. Delete old entries when definitions are updated/deleted (cascades to filters)
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenRepository : WithIdRepository<DefinitionListenModel>() {

    companion object {
        const val WORKFLOW_NAMESPACE_COLUMN = "workflow_namespace"
        const val WORKFLOW_NAME_COLUMN = "workflow_name"
        const val WORKFLOW_VERSION_COLUMN = "workflow_version"
        const val NODE_POSITION_COLUMN = "node_position"
        const val STRATEGY_COLUMN = "strategy"
        const val READ_MODE_COLUMN = "read_mode"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = DEFINITION_LISTEN_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, DefinitionListenModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            WORKFLOW_NAMESPACE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowNamespace.toString()) },
            WORKFLOW_NAME_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowName.toString()) },
            WORKFLOW_VERSION_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowVersion.toString()) },
            NODE_POSITION_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.nodePosition.toString()) },
            STRATEGY_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.strategy.name) },
            READ_MODE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.readAs.name) }
        )
    }

    override fun createModel(rs: ResultSet): DefinitionListenModel = DefinitionListenModel(
        id = getIDV7(rs, WithIdRepository.ID_COLUMN)!!,
        workflowNamespace = WorkflowNamespace(rs.getString(WORKFLOW_NAMESPACE_COLUMN)),
        workflowName = WorkflowName(rs.getString(WORKFLOW_NAME_COLUMN)),
        workflowVersion = WorkflowVersion(rs.getString(WORKFLOW_VERSION_COLUMN)),
        nodePosition = NodePosition(rs.getString(NODE_POSITION_COLUMN)),
        strategy = ListenStrategy.valueOf(rs.getString(STRATEGY_COLUMN)),
        readAs = ListenAndReadAs.valueOf(rs.getString(READ_MODE_COLUMN))
    )

    /**
     * Finds all listen tasks for a specific workflow definition.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection
     * @return List of listen task definitions for the workflow
     */
    suspend fun findByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): List<DefinitionListenModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByDefinitionSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.setString(3, version.toString())
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findByDefinitionSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $WORKFLOW_NAMESPACE_COLUMN = ?
          AND $WORKFLOW_NAME_COLUMN = ?
          AND $WORKFLOW_VERSION_COLUMN = ?
        ORDER BY $NODE_POSITION_COLUMN
        """.trimIndent()
    }

    /**
     * Finds a listen task by workflow definition and node position.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param nodePosition The position of the listen task in the workflow
     * @param connection Optional database connection
     * @return The listen task definition if found, null otherwise
     */
    suspend fun findByDefinitionAndPosition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        nodePosition: NodePosition,
        connection: Connection? = null
    ): DefinitionListenModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findByDefinitionAndPositionSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.setString(3, version.toString())
            stmt.setString(4, nodePosition.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByDefinitionAndPositionSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $WORKFLOW_NAMESPACE_COLUMN = ?
          AND $WORKFLOW_NAME_COLUMN = ?
          AND $WORKFLOW_VERSION_COLUMN = ?
          AND $NODE_POSITION_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Deletes all listen tasks for a specific workflow definition.
     * This cascades to delete associated filters.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection
     * @return Number of listen tasks deleted
     */
    suspend fun deleteByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByDefinitionSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.setString(3, version.toString())
            stmt.executeUpdate()
        }
    }

    private val deleteByDefinitionSql by lazy {
        """
        DELETE FROM $tableName
        WHERE $WORKFLOW_NAMESPACE_COLUMN = ?
          AND $WORKFLOW_NAME_COLUMN = ?
          AND $WORKFLOW_VERSION_COLUMN = ?
        """.trimIndent()
    }
}
