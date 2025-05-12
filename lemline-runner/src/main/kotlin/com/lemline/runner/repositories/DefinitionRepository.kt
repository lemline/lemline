// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DefinitionModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

@ApplicationScoped
class DefinitionRepository : Repository<DefinitionModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = "definitions"

    override val columns = listOf("id", "definition", "name", "version")

    override val keyColumns: List<String> = listOf("name", "version")

    override fun PreparedStatement.bindUpdateWith(entity: DefinitionModel) = apply {
        setString(1, entity.definition) // Sets the workflow definition
        setString(2, entity.name) // Sets the workflow name
        setString(3, entity.version) // Sets the workflow version
    }

    override fun PreparedStatement.bindInsertWith(entity: DefinitionModel) = apply {
        setString(1, entity.id) // Sets the workflow definition
        setString(2, entity.definition) // Sets the workflow definition
        setString(3, entity.name) // Sets the workflow name
        setString(4, entity.version) // Sets the workflow version
    }

    override fun PreparedStatement.bindDeleteWith(entity: DefinitionModel) = apply {
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
        id = rs.getString("id"),
        name = rs.getString("name"),
        version = rs.getString("version"),
        definition = rs.getString("definition")
    )

    /**
     * Finds all versions of a workflow by its name.
     * This method retrieves all workflows from the database that match the given name.
     *
     * @param name The name of the workflow to search for.
     * @param connection An optional database connection. If null, a new connection will be created.
     * @return A list of `WorkflowModel` instances matching the given name.
     */
    fun listByName(name: String, connection: Connection? = null): List<DefinitionModel> {
        val sql = """
            SELECT * FROM $tableName
            WHERE name = ?
        """.trimIndent()

        return withConnection(connection) {
            it.prepareStatement(sql).use { stmt ->
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
    }

    /**
     * Finds a workflow by its name and version.
     * This method uses a native SQL query to retrieve the workflow from the database.
     *
     * @param name The name of the workflow
     * @param version The version of the workflow
     * @return The workflow model if found, null otherwise
     */
    fun findByNameAndVersion(name: String, version: String, connection: Connection? = null): DefinitionModel? {
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
