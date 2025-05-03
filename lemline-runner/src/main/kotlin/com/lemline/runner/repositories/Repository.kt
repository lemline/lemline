// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.models.UuidV7Entity
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

abstract class Repository<T : UuidV7Entity> {

    internal abstract val databaseManager: DatabaseManager

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
     * Defines the columns that constitute the primary or unique key for the entity.
     * Used for UPSERT's ON CONFLICT clause and UPDATE's WHERE clause.
     * Defaults to `listOf("id")`.
     */
    protected abstract val keyColumns: List<String>

    /**
     * Populates the `PreparedStatement` with the values from the given entity.
     * This method must be implemented by concrete repositories to map the entity's properties
     * to the corresponding SQL parameters in the prepared statement.
     *
     * @param entity The entity containing the values to set in the statement
     * @return The `PreparedStatement` with the populated values
     */
    protected abstract fun PreparedStatement.bindUpdateWith(entity: T): PreparedStatement


    protected abstract fun PreparedStatement.bindInsertWith(entity: T): PreparedStatement

    /**
     * Executes a block of code within a database transaction.
     *
     * This method acquires a JDBC connection from the datasource, begins a transaction,
     * executes the provided block of code, and commits the transaction if the block
     * completes successfully. If an exception occurs during the execution of the block,
     * the transaction is rolled back to ensure data consistency. The connection is
     * always closed and returned to the pool after the block is executed.
     *
     * @param block A lambda function that takes a `Connection` as a parameter and returns a result of type `T`.
     * @return The result of the block execution.
     * @throws Exception If an error occurs during the execution of the block or transaction management.
     */
    internal fun <T> withTransaction(block: (connection: Connection) -> T): T {
        // Acquire a JDBC connection from the pool and begin a transaction
        val connection = databaseManager.datasource.connection
        connection.autoCommit = false

        val out: T
        try {
            out = block(connection)
            // Commit the transaction to release locks and persist changes
            connection.commit()
        } catch (e: Exception) {
            // On any error, roll back the transaction to release locks and avoid partial processing
            connection.rollback()
            throw e  // rethrow
        } finally {
            // Return connection to the pool
            connection.close()
        }

        return out
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
     * Inserts a new entity
     *
     * @param entity The entity to insert
     */
    fun insert(entity: T, connection: Connection? = null): Int {
        val sql = getInsertSql()
        var updated = 0

        withConnection(connection) { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.bindInsertWith(entity)
                updated += stmt.executeUpdate()
            }
        }

        return updated
    }

    /**
     * Updates an existing entity.
     *
     * @param entity The entity to upsert
     */
    fun update(entity: T, connection: Connection? = null): Int {
        val sql = getUpdateSql()
        var updated = 0

        withConnection(connection) {
            it.prepareStatement(sql).use { stmt ->
                stmt.bindUpdateWith(entity)
                updated += stmt.executeUpdate()
            }
        }

        return updated
    }

    /**
     * Inserts a list of new entities
     *
     * @param entities The list of entities to insert
     */
    fun insert(entities: List<T>, connection: Connection? = null): Int {
        if (entities.isEmpty()) return 0
        val sql = getInsertSql()
        var updated = 0

        withConnection(connection) { conn ->
            conn.prepareStatement(sql).use { stmt ->
                for (entity in entities) {
                    stmt.bindInsertWith(entity)
                    stmt.addBatch()
                }
                // execute once, receive one update‑count per row
                val counts = stmt.executeBatch()
                // JDBC spec: 0 → row not found, ≥1 → row(s) modified
                updated += counts.count { it > 0 }
            }
        }

        return updated
    }

    /**
     * Updates a list of existing entities.
     *
     * Warning: this method does not throw but returns the number of successful requests
     *
     * @param entities The list of entities to upsert
     */
    fun update(entities: List<T>, connection: Connection? = null): Int {
        if (entities.isEmpty()) return 0
        val sql = getUpdateSql()
        var updated = 0

        withConnection(connection) { conn ->
            conn.prepareStatement(sql).use { stmt ->
                for (entity in entities) {
                    stmt.bindUpdateWith(entity)
                    stmt.addBatch()
                }
                // execute once, receive one update‑count per row
                val counts: IntArray = stmt.executeBatch()
                // JDBC spec: 0 → row not found, ≥1 → row(s) modified
                updated += counts.count { it > 0 }
            }
        }

        return updated
    }

    /**
     * Retrieves an entity by its ID.
     * This method uses a native SQL query to fetch the record.
     *
     * @param id The ID of the entity to find
     * @return The entity if found, null otherwise
     */
    fun findById(id: String, connection: Connection? = null): T? {
        val sql = """
            SELECT * FROM $tableName
            WHERE id = ?
            LIMIT 1
        """.trimIndent()

        return withConnection(connection) {
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
    fun listAll(connection: Connection? = null): List<T> {
        val sql = "SELECT * FROM $tableName"

        return withConnection(connection) {
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
    fun deleteAll(connection: Connection? = null): Int {
        val sql = "DELETE FROM $tableName"

        return withConnection(connection) {
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
    fun delete(messages: List<T>, connection: Connection? = null): Int {
        if (messages.isEmpty()) return 0

        val sql = "DELETE FROM $tableName WHERE id = ?"

        return withConnection(connection) {
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
    fun count(connection: Connection? = null): Long {
        val sql = "SELECT COUNT(id) FROM $tableName"

        return withConnection(connection) {
            it.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    private fun getUpdateSql(): String {
        val setClause = columns.filterNot { it in (keyColumns + "id") }.joinToString { "${q(it)} = ?" }
        val whereClause = keyColumns.joinToString(separator = " AND ") { "${q(it)} = ?" }

        return """
            UPDATE $tableName
            SET $setClause
            WHERE $whereClause
        """.trimIndent()
    }

    private fun getInsertSql(): String {
        val colsCsv = columns.joinToString { q(it) }  // Comma-separated column names, e.g., "id","message",…
        val valsCsv = columns.joinToString { "?" }    // Comma-separated placeholders, e.g., ?,?,?

        return when (databaseManager.dbType) {
            DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> """
                    INSERT INTO $tableName ($colsCsv)
                    VALUES ($valsCsv)
                    ON CONFLICT DO NOTHING
                """.trimIndent()

            DB_TYPE_MYSQL -> """
                    INSERT IGNORE INTO $tableName ($colsCsv)
                    VALUES ($valsCsv)
                """.trimIndent()

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    // Helper that returns the dialect-specific quoting of an SQL identifier.
    private fun q(id: String): String = when (databaseManager.dbType) {
        DB_TYPE_POSTGRESQL -> "\"$id\""     // → "status"
        DB_TYPE_MYSQL -> "`$id`"            // → `status`
        DB_TYPE_IN_MEMORY -> id             // H2: bare identifiers are fine
        else -> id
    }

    /**
     * Executes a block of code with a database connection.
     * If a connection is provided, it uses that connection; otherwise, it retrieves a connection
     * from the `databaseManager`'s datasource and ensures it is properly closed after use.
     *
     * @param connection An optional database connection to use. If null, a new connection is obtained.
     * @param block The block of code to execute with the connection.
     * @return The result of the block execution.
     */
    protected fun <R> withConnection(connection: Connection?, block: (Connection) -> R): R = when (connection) {
        null -> databaseManager.datasource.connection.use { block(it) }
        else -> block(connection)
    }

    protected fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()
}
