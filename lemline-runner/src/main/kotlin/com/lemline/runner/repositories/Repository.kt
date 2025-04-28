// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.UuidV7Entity
import jakarta.transaction.Transactional
import java.sql.Connection
import java.sql.ResultSet

abstract class Repository<T : UuidV7Entity> {

    abstract val databaseManager: DatabaseManager

    abstract val tableName: String

    /**
     * Persists an entity to the database.
     * This method must be implemented by concrete repositories to handle
     * the specific persistence logic for their entity type.
     * The implementation should handle both insert and update operations.
     *
     * @param entity The entity to persist
     */
    @Transactional
    abstract fun persist(entity: T)

    /**
     * Persists multiple entities to the database in a single transaction.
     * This method uses batch operations for better performance.
     * The implementation should handle both insert and update operations.
     *
     * @param entities The list of entities to persist
     */
    @Transactional
    abstract fun persist(entities: List<T>)

    /**
     * Retrieves an entity by its ID.
     * This method uses a native SQL query to fetch the record.
     *
     * @param id The ID of the entity to find
     * @return The entity if found, null otherwise
     */
    @Transactional
    fun findById(id: String): T? {
        val sql = """
            SELECT * FROM $tableName
            WHERE id = ?
            LIMIT 1
        """.trimIndent()

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }
    }

    /**
     * Retrieves all entities from the table.
     * This method uses a native SQL query to fetch all records.
     * Use with caution as this operation can be expensive for large tables.
     *
     * @return List of all entities in the table
     */
    @Transactional
    fun listAll(): List<T> {
        val sql = "SELECT * FROM $tableName"

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
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
     * Creates a model instance from a ResultSet.
     * This method must be implemented by concrete repositories to handle
     * the specific mapping of database columns to model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A new model instance populated with data from the ResultSet
     */
    protected abstract fun createModel(rs: ResultSet): T

    /**
     * Creates a list of model instances from a ResultSet.
     * This method must be implemented by concrete repositories to handle
     * the specific mapping of database columns to model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A list of new model instance populated with data from the ResultSet
     */
    protected fun createModels(rs: ResultSet): List<T> = buildList {
        while (rs.next()) {
            add(createModel(rs))
        }
    }

    /**
     * Deletes all workflows from the database.
     * This method is transactional and uses a native SQL query to delete all workflows.
     * Use with caution as this operation cannot be undone.
     *
     * @return The number of workflows deleted
     */
    @Transactional
    fun deleteAll(): Int {
        val sql = "DELETE FROM $tableName"

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Deletes a list of messages from the outbox table.
     * This method uses batch operations for better performance.
     * The operation is transactional and will be rolled back if any deletion fails.
     *
     * @param messages The list of messages to delete
     * @return The number of messages successfully deleted
     */
    @Transactional
    fun delete(messages: List<T>): Int {
        if (messages.isEmpty()) return 0

        val sql = "DELETE FROM $tableName WHERE id = ?"

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                for (message in messages) {
                    stmt.setString(1, message.id)
                    stmt.addBatch()
                }
                stmt.executeBatch().sum()
            }
        }
    }

    protected fun <R> withConnection(block: (Connection) -> R): R {
        val connection = databaseManager.resolveUserSelectedDatasource().connection
        return block(connection)
    }
}
