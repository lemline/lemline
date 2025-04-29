// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.models.UuidV7Entity
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

abstract class Repository<T : UuidV7Entity> {

    protected abstract val databaseManager: DatabaseManager

    abstract val tableName: String

    /**
     * Returns the column names for the table, comma-separated.
     * This should include all columns that are part of the upsert operation.
     */
    protected abstract val columns: List<String>

    /**
     * Helper that returns the dialect-specific quoting of an SQL identifier.
     * Feel free to extend when you add new RDBMS types.
     */
    private fun q(id: String): String = when (databaseManager.dbType) {
        DB_TYPE_POSTGRESQL -> "\"$id\""          // → "status"
        DB_TYPE_MYSQL -> "`$id`"            // → `status`
        DB_TYPE_IN_MEMORY -> id                 // H2: bare identifiers are fine
        else -> id
    }

    /**
     * Build an UPSERT / MERGE statement suited to the current RDBMS.
     * Assumes `columns` is a *List* declared in each concrete repository, e.g.
     *
     *     override val columns = listOf("id", "message", "status", …)
     */
    protected fun getUpsertSql(): String {
        // id column is never updated on conflict
        val nonIdCols = columns.filterNot { it == "id" }
        val colsCsv = columns.joinToString { q(it) }            // "id","message",…
        val valsCsv = columns.joinToString { "?" }              // ?,?,?
        val updates = nonIdCols.joinToString { "${q(it)} = EXCLUDED.${q(it)}" }
        val updatesMy = nonIdCols.joinToString { "${q(it)} = VALUES(${q(it)})" }

        return when (databaseManager.dbType) {
            DB_TYPE_POSTGRESQL -> """
            INSERT INTO $tableName ($colsCsv)
            VALUES ($valsCsv)
            ON CONFLICT (${q("id")}) DO UPDATE SET $updates
        """.trimIndent()

            DB_TYPE_MYSQL -> """
            INSERT INTO $tableName ($colsCsv)
            VALUES ($valsCsv)
            ON DUPLICATE KEY UPDATE $updatesMy
        """.trimIndent()

            DB_TYPE_IN_MEMORY -> """
            MERGE INTO $tableName ($colsCsv)
            KEY (${q("id")})
            VALUES ($valsCsv)
        """.trimIndent()

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    /**
     * Persists an entity to the database.
     * This method must be implemented by concrete repositories to handle
     * the specific persistence logic for their entity type.
     * The implementation should handle both insert and update operations.
     *
     * @param entity The entity to persist
     */
    abstract fun persist(entity: T)

    /**
     * Persists multiple entities to the database in a single transaction.
     * This method uses batch operations for better performance.
     * The implementation should handle both insert and update operations.
     *
     * @param entities The list of entities to persist
     */
    abstract fun persist(entities: List<T>)

    /**
     * Retrieves an entity by its ID.
     * This method uses a native SQL query to fetch the record.
     *
     * @param id The ID of the entity to find
     * @return The entity if found, null otherwise
     */
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
    internal abstract fun createModel(rs: ResultSet): T

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

    /**
     * Counts the total number of records in the table.
     * This method uses a native SQL query to count all records.
     *
     * @return The total number of records in the table
     */
    fun count(): Long {
        val sql = "SELECT COUNT(*) FROM $tableName"

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    protected fun <R> withConnection(block: (Connection) -> R): R =
        databaseManager.datasource.connection.use { conn ->
            block(conn)
        }

    // 1️⃣ define an extension for clarity
    protected fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()
}
