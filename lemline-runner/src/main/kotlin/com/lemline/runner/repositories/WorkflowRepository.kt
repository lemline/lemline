// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WorkflowModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

@ApplicationScoped
class WorkflowRepository : Repository<WorkflowModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = "workflows"

    override val columns = listOf("id", "definition", "name", "version")

    override val keyColumns: List<String> = listOf("name", "version")

    /**
     * Populates the `PreparedStatement` with the values from the given workflow entity.
     * This method maps the workflow model's properties to the corresponding SQL parameters
     * in the prepared statement.
     *
     * @param entity The workflow model containing the values to set in the statement
     * @return The `PreparedStatement` with the populated values
     */
    override fun PreparedStatement.bindUpdateWith(entity: WorkflowModel) = apply {
        setString(1, entity.definition) // Sets the workflow definition
        setString(2, entity.name) // Sets the workflow name
        setString(3, entity.version) // Sets the workflow version
    }

    override fun PreparedStatement.bindInsertWith(entity: WorkflowModel) = apply {
        setString(1, entity.id) // Sets the workflow definition
        setString(2, entity.definition) // Sets the workflow definition
        setString(3, entity.name) // Sets the workflow name
        setString(4, entity.version) // Sets the workflow version
    }

    /**
     * Creates a model instance from a ResultSet.
     * Maps the database columns to the workflow model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A new workflow model instance populated with data from the ResultSet
     */
    override fun createModel(rs: ResultSet): WorkflowModel = WorkflowModel(
        id = rs.getString("id"),
        name = rs.getString("name"),
        version = rs.getString("version"),
        definition = rs.getString("definition")
    )

    /**
     * Finds a workflow by its name and version.
     * This method uses a native SQL query to retrieve the workflow from the database.
     *
     * @param name The name of the workflow
     * @param version The version of the workflow
     * @return The workflow model if found, null otherwise
     */
    fun findByNameAndVersion(name: String, version: String, connection: Connection? = null): WorkflowModel? {
        val sql = """
            SELECT * FROM $tableName
            WHERE name = ?
            AND version = ?
            LIMIT 1
        """.trimIndent()

        return withConnection(connection) {
            it.prepareStatement(sql).use { stmt ->
                stmt.apply {
                    setString(1, name)
                    setString(2, version)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }
    }
}
