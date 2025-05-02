// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.models.UuidV7Entity
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant

abstract class Repository<T : UuidV7Entity> {

    protected abstract val databaseManager: DatabaseManager

    /**
     * The name of the database table associated with this repository.
     * This property must be implemented by concrete repositories to specify
     * the table name used for database operations.
     */
    internal abstract val tableName: String

    /**
     * Returns the column names for the table, comma-separated.
     * This should include all columns that are part of the upsert operation.
     */
    protected abstract val columns: List<String>

    /**
     * Populates the `PreparedStatement` with the values from the given entity.
     * This method must be implemented by concrete repositories to map the entity's properties
     * to the corresponding SQL parameters in the prepared statement.
     *
     * @param entity The entity containing the values to set in the statement
     * @return The `PreparedStatement` with the populated values
     */
    protected abstract fun PreparedStatement.with(entity: T): PreparedStatement

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
     * Inserts a new entity into the database.
     * This method inserts the entity if it does not exist or fails it if it already exists.
     *
     * @param entity The entity to insert
     */
    fun insert(entity: T) = persist(entity, false)

    /**
     * Performs an upsert operation for a single entity.
     * This method inserts the entity if it does not exist or updates it if it already exists.
     *
     * @param entity The entity to upsert
     */
    fun upsert(entity: T) = persist(entity, true)

    /**
     *
     * NOTE: this method is deactivated because not used AND have consistency issues between databases
     * as PostgreSQL always runs statements in an implicit transaction,
     *
     * Inserts a list of entities into the database.
     * This method inserts the entities if they do not exist and fails if they already exist.
     *
     * @param entities The list of entities to insert
     */
    //fun insert(entities: List<T>) = persist(entities, false)

    /**
     * Performs an upsert operation for a list of entities.
     * This method inserts the entities if they do not exist or updates them if they already exist.
     *
     * Warning: this method does not throw but returns the number of successful requests
     *
     * @param entities The list of entities to upsert
     */
    fun upsert(entities: List<T>) = persist(entities, true)

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

    private fun getUpsertSql(): String {
        val colsCsv = columns.joinToString { q(it) }  // Comma-separated column names, e.g., "id","message",…
        val valsCsv = columns.joinToString { "?" }    // Comma-separated placeholders, e.g., ?,?,?

        val nonIdCols = columns.filterNot { it == "id" }
        val updates = nonIdCols.joinToString { "${q(it)} = EXCLUDED.${q(it)}" } // For PostgreSQL
        val updatesMy = nonIdCols.joinToString { "${q(it)} = VALUES(${q(it)})" } // For MySQL

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

    private fun getInsertSql(): String {
        val colsCsv = columns.joinToString { q(it) }  // Comma-separated column names, e.g., "id","message",…
        val valsCsv = columns.joinToString { "?" }    // Comma-separated placeholders, e.g., ?,?,?

        return when (databaseManager.dbType) {
            DB_TYPE_POSTGRESQL -> """
                INSERT INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
            """.trimIndent()

            DB_TYPE_MYSQL -> """
                INSERT INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
            """.trimIndent()

            DB_TYPE_IN_MEMORY -> """
                INSERT INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
            """.trimIndent()

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    // Helper that returns the dialect-specific quoting of an SQL identifier.
    private fun q(id: String): String = when (databaseManager.dbType) {
        DB_TYPE_POSTGRESQL -> "\"$id\""          // → "status"
        DB_TYPE_MYSQL -> "`$id`"            // → `status`
        DB_TYPE_IN_MEMORY -> id                 // H2: bare identifiers are fine
        else -> id
    }

    private fun persist(entity: T, force: Boolean) {
        val sql = if (force) getUpsertSql() else getInsertSql()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.with(entity)
                stmt.executeUpdate()
            }
        }
    }

    private fun persist(entities: List<T>, force: Boolean): Int {
        if (entities.isEmpty()) return 0
        val sql = if (force) getUpsertSql() else getInsertSql()

        val failed = mutableListOf<Pair<T, Exception>>()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                try {
                    // ── fast path ── batch everything
                    for (entity in entities) {
                        stmt.with(entity)
                        stmt.addBatch()
                    }
                    val counts = stmt.executeBatch()

                    // no exception, but look for failures
                    counts.forEachIndexed { index, i ->
                        if (i == Statement.EXECUTE_FAILED) {
                            failed += entities[index] to BatchUpdateException()
                        }
                    }

                } catch (batchEx: SQLException) {
                    // ── slow path ── clear batch and retry row‑by‑row
                    stmt.clearBatch()

                    for (entity in entities) {
                        try {
                            stmt.with(entity)
                            stmt.executeUpdate()
                        } catch (e: SQLException) {
                            failed += entity to e                // remember the bad one
                        }
                    }
                }
            }
        }

        return entities.size - failed.size
    }

    /**
     * WARNING - this way of using the connection prevents using transactions
     * because the connection is automatically closed after the block execution.
     * Transactions would require the connection to remain open until explicitly committed or rolled back.
     */
    protected fun <R> withConnection(block: (Connection) -> R): R =
        databaseManager.datasource.connection.use { conn ->
            block(conn)
        }

    protected fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()
}
