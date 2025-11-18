// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DefinitionModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

const val DEFINITION_TABLE = "lemline_definitions"

@ApplicationScoped
class DefinitionRepository : Repository<DefinitionModel>() {

    companion object {
        internal const val WORKFLOW_DEFINITION_COLUMN = "definition"
        internal const val WORKFLOW_NAMESPACE_COLUMN = "namespace"
        internal const val WORKFLOW_NAME_COLUMN = "name"
        internal const val WORKFLOW_VERSION_COLUMN = "version"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = DEFINITION_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, DefinitionModel, Int) -> Unit> = mapOf(
        WORKFLOW_DEFINITION_COLUMN to { stmt: PreparedStatement, entity: DefinitionModel, idx: Int ->
            stmt.setString(idx, entity.definition)
        },
        WORKFLOW_NAMESPACE_COLUMN to { stmt: PreparedStatement, entity: DefinitionModel, idx: Int ->
            stmt.setString(idx, entity.namespace.toString())
        },
        WORKFLOW_NAME_COLUMN to { stmt: PreparedStatement, entity: DefinitionModel, idx: Int ->
            stmt.setString(idx, entity.name.toString())
        },
        WORKFLOW_VERSION_COLUMN to { stmt: PreparedStatement, entity: DefinitionModel, idx: Int ->
            stmt.setString(idx, entity.version.toString())
        }
    )

    override val keyColumns: List<String> = listOf(WORKFLOW_NAME_COLUMN, WORKFLOW_VERSION_COLUMN)

    /**
     * Creates a model instance from a ResultSet.
     * Maps the database columns to the workflow model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A new workflow model instance populated with data from the ResultSet
     */
    override fun createModel(rs: ResultSet): DefinitionModel = DefinitionModel(
        namespace = WorkflowNamespace(rs.getString(WORKFLOW_NAMESPACE_COLUMN)),
        name = WorkflowName(rs.getString(WORKFLOW_NAME_COLUMN)),
        version = WorkflowVersion(rs.getString(WORKFLOW_VERSION_COLUMN)),
        definition = rs.getString(WORKFLOW_DEFINITION_COLUMN)
    )

    /**
     * Retrieves all workflow definitions within the specified namespace from the database.
     * This method executes a query to list all records in the namespace and maps the results
     * to a list of `DefinitionModel` instances.
     *
     * @param namespace The namespace to search for workflow definitions.
     * @param connection An optional database connection. If null, a new connection will be created.
     * @return A list of `DefinitionModel` instances representing the workflow definitions within the namespace.
     */
    suspend fun listAllInNamespace(
        namespace: WorkflowNamespace,
        connection: Connection? = null
    ): List<DefinitionModel> = withConnection(connection) {
        it.prepareStatement(listAllInNamespaceSql).use { stmt ->
            stmt.apply {
                setString(1, namespace.toString())
            }
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val listAllInNamespaceSql by lazy { "SELECT * FROM $tableName WHERE namespace = ?" }

    /**
     * Deletes all workflows from the database that belong to the given namespace.
     * This method is transactional and uses a native SQL query to delete all workflows.
     * Use with caution as this operation cannot be undone.
     *
     * @return The number of workflows deleted
     */
    suspend fun deleteAllInNamespace(namespace: WorkflowNamespace, connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(deleteAllInNamespaceSql).use { stmt ->
                stmt.apply {
                    setString(1, namespace.toString())
                }
                stmt.executeUpdate()
            }
        }

    private val deleteAllInNamespaceSql by lazy { "DELETE FROM $tableName WHERE namespace = ?" }

    /**
     * Counts the total number of records in the table for the given namespace.
     * This method uses a native SQL query to count all records.
     *
     * @return The total number of records in the table
     */
    suspend fun countAllInNamespace(namespace: WorkflowNamespace, connection: Connection? = null): Long =
        withConnection(connection) { conn ->
            conn.prepareStatement(countAllInNamespaceSql).use { stmt ->
                stmt.apply {
                    setString(1, namespace.toString())
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    private val countAllInNamespaceSql by lazy { "SELECT COUNT(*) FROM $tableName WHERE namespace = ?" }

    /**
     * Finds all versions of a workflow by its namespace and name.
     * This method retrieves all workflows from the database that match the given name.
     *
     * @param name The name of the workflow to search for.
     * @param connection An optional database connection. If null, a new connection will be created.
     * @return A list of `WorkflowModel` instances matching the given name.
     */
    suspend fun listByName(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        connection: Connection? = null
    ): List<DefinitionModel> = withConnection(connection) {
        it.prepareStatement(listByNameSql).use { stmt ->
            stmt.apply {
                setString(1, namespace.toString())
                setString(2, name.toString())
            }
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val listByNameSql by lazy { "SELECT * FROM $tableName WHERE namespace = ? AND name = ?" }

    /**
     * Finds a workflow by its name and version.
     * This method uses a native SQL query to retrieve the workflow from the database.
     *
     * @param name The name of the workflow
     * @param version The version of the workflow
     * @return The workflow model if found, null otherwise
     */
    suspend fun findByNameAndVersion(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): DefinitionModel? = withConnection(connection) {
        it.prepareStatement(findByNameAndVersionSql).use { stmt ->
            stmt.apply {
                setString(1, namespace.toString())
                setString(2, name.toString())
                setString(3, version.toString())
            }
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByNameAndVersionSql by lazy { "SELECT * FROM $tableName WHERE namespace = ? AND name = ? AND version = ? LIMIT 1" }
}
