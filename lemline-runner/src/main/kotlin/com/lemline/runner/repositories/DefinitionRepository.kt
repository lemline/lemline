// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DEFINITION_TABLE
import com.lemline.runner.models.DefinitionModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

@ApplicationScoped
class DefinitionRepository : Repository<DefinitionModel>() {

    companion object {
        internal const val WORKFLOW_DEFINITION_COLUMN = "definition"
        internal const val WORKFLOW_NAME_COLUMN = "name"
        internal const val WORKFLOW_VERSION_COLUMN = "version"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = DEFINITION_TABLE

    override val insertColumns = listOf(WORKFLOW_DEFINITION_COLUMN, WORKFLOW_NAME_COLUMN, WORKFLOW_VERSION_COLUMN)

    override val updateColumns = listOf(WORKFLOW_DEFINITION_COLUMN)

    override val keyColumns: List<String> = listOf(WORKFLOW_NAME_COLUMN, WORKFLOW_VERSION_COLUMN)

    // MUST be in the same order as insertColumns
    override fun bindInsertWith(stmt: PreparedStatement, entity: DefinitionModel) = stmt.apply {
        setString(1, entity.definition) // Sets the workflow definition
        setString(2, entity.name) // Sets the workflow name
        setString(3, entity.version) // Sets the workflow version
    }

    // MUST be in the same order as updateColumns
    override fun bindUpdateWith(stmt: PreparedStatement, entity: DefinitionModel) = stmt.apply {
        setString(1, entity.definition) // Sets the workflow definition
    }

    // MUST be in the same order as keyColumns
    override fun bindDeleteWith(stmt: PreparedStatement, entity: DefinitionModel) = stmt.apply {
        setString(1, entity.name) // Bind name to the first parameter
        setString(2, entity.version) // Bind the version to the second parameter
    }

    /**
     * Creates a model instance from a ResultSet.
     * Maps the database columns to the workflow model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A new workflow model instance populated with data from the ResultSet
     */
    override fun createModel(rs: ResultSet): DefinitionModel = DefinitionModel(
        name = rs.getString(WORKFLOW_NAME_COLUMN),
        version = rs.getString(WORKFLOW_VERSION_COLUMN),
        definition = rs.getString(WORKFLOW_DEFINITION_COLUMN)
    )

    /**
     * Finds all versions of a workflow by its name.
     * This method retrieves all workflows from the database that match the given name.
     *
     * @param name The name of the workflow to search for.
     * @param connection An optional database connection. If null, a new connection will be created.
     * @return A list of `WorkflowModel` instances matching the given name.
     */
    suspend fun listByName(name: String, connection: Connection? = null): List<DefinitionModel> =
        withConnection(connection) {
            it.prepareStatement(listByNameSql).use { stmt ->
                stmt.apply {
                    setString(1, name)
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

    private val listByNameSql by lazy { "SELECT * FROM $tableName WHERE name = ?" }

    /**
     * Finds a workflow by its name and version.
     * This method uses a native SQL query to retrieve the workflow from the database.
     *
     * @param name The name of the workflow
     * @param version The version of the workflow
     * @return The workflow model if found, null otherwise
     */
    suspend fun findByNameAndVersion(name: String, version: String, connection: Connection? = null): DefinitionModel? =
        withConnection(connection) {
            it.prepareStatement(findByNameAndVersionSql).use { stmt ->
                stmt.apply {
                    setString(1, name)
                    setString(2, version)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByNameAndVersionSql by lazy { "SELECT * FROM $tableName WHERE name = ? AND version = ? LIMIT 1" }
}
