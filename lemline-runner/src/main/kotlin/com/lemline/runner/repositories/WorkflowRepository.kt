// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WorkflowModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.sql.ResultSet

@ApplicationScoped
class WorkflowRepository : Repository<WorkflowModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = "workflows"

    override val columns: String = "id, name, version, definition"
    override val values: String = "?, ?, ?, ?"

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
    @Transactional
    fun findByNameAndVersion(name: String, version: String): WorkflowModel? {
        val sql = """
            SELECT * FROM $tableName
            WHERE name = ?
            AND version = ?
            LIMIT 1
        """.trimIndent()

        return withConnection {
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

    /**
     * Persists a workflow to the database.
     * This method is transactional and uses native SQL for optimal performance.
     *
     * @param entity The workflow to persist
     */
    @Transactional
    override fun persist(entity: WorkflowModel) {
        val sql = getUpsertSql()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entity.id)
                stmt.setString(2, entity.name)
                stmt.setString(3, entity.version)
                stmt.setString(4, entity.definition)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Persists multiple workflows to the database in a single batch operation.
     * This method uses database-specific batch operations for optimal performance.
     *
     * @param entities The list of workflows to persist
     */
    @Transactional
    override fun persist(entities: List<WorkflowModel>) {
        if (entities.isEmpty()) return

        val sql = getUpsertSql()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                for (workflow in entities) {
                    stmt.setString(1, workflow.id)
                    stmt.setString(2, workflow.name)
                    stmt.setString(3, workflow.version)
                    stmt.setString(4, workflow.definition)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }
}
