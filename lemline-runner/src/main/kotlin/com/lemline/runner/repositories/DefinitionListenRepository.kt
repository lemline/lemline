// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DefinitionListenModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val DEFINITION_LISTEN_TABLE = "lemline_definition_listens"

/**
 * Repository for managing listen filter definitions extracted from workflow definitions.
 *
 * This repository stores listen filter configurations that are extracted when
 * workflow definitions are registered. The stored filters enable efficient
 * CloudEvent routing to matching listeners without parsing workflow definitions
 * at runtime.
 *
 * ## Usage
 *
 * When a workflow definition containing listen tasks is saved:
 * 1. Extract all listen task filters from the workflow
 * 2. Insert each filter using this repository
 * 3. Delete old filters when definitions are updated/deleted
 *
 * When a CloudEvent arrives:
 * 1. Query filters by event type/source/subject
 * 2. Match against active listeners for those filters
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenRepository : Repository<DefinitionListenModel>() {

    companion object {
        const val ID_COLUMN = "id"
        const val WORKFLOW_NAMESPACE_COLUMN = "workflow_namespace"
        const val WORKFLOW_NAME_COLUMN = "workflow_name"
        const val WORKFLOW_VERSION_COLUMN = "workflow_version"
        const val NODE_POSITION_COLUMN = "node_position"
        const val FILTER_INDEX_COLUMN = "filter_index"
        const val EVENT_TYPE_COLUMN = "event_type"
        const val EVENT_SOURCE_COLUMN = "event_source"
        const val EVENT_SUBJECT_COLUMN = "event_subject"
        const val CORRELATIONS_COLUMN = "correlations"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = DEFINITION_LISTEN_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, DefinitionListenModel, Int) -> Unit> = mapOf(
        ID_COLUMN to { stmt, entity, idx -> setIDV7(stmt, idx, entity.id) },
        WORKFLOW_NAMESPACE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowNamespace.toString()) },
        WORKFLOW_NAME_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowName.toString()) },
        WORKFLOW_VERSION_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.workflowVersion.toString()) },
        NODE_POSITION_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.nodePosition.toString()) },
        FILTER_INDEX_COLUMN to { stmt, entity, idx -> stmt.setInt(idx, entity.filterIndex) },
        EVENT_TYPE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventType) },
        EVENT_SOURCE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventSource) },
        EVENT_SUBJECT_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventSubject) },
        CORRELATIONS_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.correlations) }
    )

    override val keyColumns: List<String> = listOf(ID_COLUMN)

    override fun createModel(rs: ResultSet): DefinitionListenModel = DefinitionListenModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        workflowNamespace = WorkflowNamespace(rs.getString(WORKFLOW_NAMESPACE_COLUMN)),
        workflowName = WorkflowName(rs.getString(WORKFLOW_NAME_COLUMN)),
        workflowVersion = WorkflowVersion(rs.getString(WORKFLOW_VERSION_COLUMN)),
        nodePosition = NodePosition(rs.getString(NODE_POSITION_COLUMN)),
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN),
        eventType = rs.getString(EVENT_TYPE_COLUMN),
        eventSource = rs.getString(EVENT_SOURCE_COLUMN),
        eventSubject = rs.getString(EVENT_SUBJECT_COLUMN),
        correlations = rs.getString(CORRELATIONS_COLUMN)
    )

    /**
     * Finds a listen filter by its ID.
     *
     * @param id The unique identifier
     * @param connection Optional database connection
     * @return The filter if found, null otherwise
     */
    suspend fun findById(id: IDV7, connection: Connection? = null): DefinitionListenModel? =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByIdSql).use { stmt ->
                setIDV7(stmt, 1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByIdSql by lazy {
        "SELECT * FROM $tableName WHERE $ID_COLUMN = ?"
    }

    /**
     * Finds all listen filters for a specific workflow definition.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection
     * @return List of listen filter definitions for the workflow
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
        ORDER BY $NODE_POSITION_COLUMN, $FILTER_INDEX_COLUMN
        """.trimIndent()
    }

    /**
     * Deletes all listen filters for a specific workflow definition.
     * Used when a definition is deleted or updated.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection
     * @return Number of filters deleted
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

    /**
     * Finds all filters that match a CloudEvent's type and optionally source/subject.
     * Used for efficient event routing to potential listeners.
     *
     * @param eventType The CloudEvent type to match
     * @param connection Optional database connection
     * @return List of matching filter definitions
     */
    suspend fun findByEventType(
        eventType: String,
        connection: Connection? = null
    ): List<DefinitionListenModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByEventTypeSql).use { stmt ->
            stmt.setString(1, eventType)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findByEventTypeSql by lazy {
        "SELECT * FROM $tableName WHERE $EVENT_TYPE_COLUMN = ?"
    }

    /**
     * Finds all filters that have no event type (wildcard filters that match any event).
     *
     * @param connection Optional database connection
     * @return List of wildcard filter definitions
     */
    suspend fun findWildcardFilters(
        connection: Connection? = null
    ): List<DefinitionListenModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findWildcardFiltersSql).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findWildcardFiltersSql by lazy {
        "SELECT * FROM $tableName WHERE $EVENT_TYPE_COLUMN IS NULL"
    }
}
