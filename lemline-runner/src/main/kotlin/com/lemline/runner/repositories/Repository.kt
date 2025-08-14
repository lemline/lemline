// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class Repository<T> {

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
     */
    protected abstract val keyColumns: List<String>

    /**
     * Populates the `PreparedStatement` with the values from the given entity for an update operation.
     * This method must be implemented by concrete repositories to map the entity's properties
     * to the corresponding SQL parameters in the prepared statement.
     *
     * @param entity The entity containing the values to set in the statement
     * @return The `PreparedStatement` with the populated values
     */
    protected abstract fun PreparedStatement.bindUpdateWith(entity: T): PreparedStatement


    /**
     * Populates the `PreparedStatement` with the values from the given entity for an insert operation.
     * This method must be implemented by concrete repositories to map the entity's properties
     * to the corresponding SQL parameters in the prepared statement.
     *
     * @param entity The entity containing the values to set in the statement
     * @return The `PreparedStatement` with the populated values
     */
    protected abstract fun PreparedStatement.bindInsertWith(entity: T): PreparedStatement

    /**
     * Populates the `PreparedStatement` with the values from the given entity for a delete operation.
     * This method must be implemented by concrete repositories to map the entity's properties
     * to the corresponding SQL parameters in the prepared statement.
     *
     * @param entity The entity containing the key values.
     * @return The PreparedStatement with key values bound.
     */
    protected abstract fun PreparedStatement.bindDeleteWith(entity: T): PreparedStatement

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
    internal suspend fun <T> withTransaction(block: suspend (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            databaseManager.datasource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    block(connection).also {
                        // Commit the transaction to release locks and persist changes
                        connection.commit()
                    }
                } catch (t: Throwable) {
                    // On any error, roll back the transaction to release locks and avoid partial processing
                    connection.rollback()
                    throw t
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
     * Inserts a new entity.
     */
    suspend fun insert(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        val sql = getInsertSql()
        conn.prepareStatement(sql).use { stmt ->
            stmt.bindInsertWith(entity)
            stmt.executeUpdate()
        }
    }

    /**
     * Inserts a list of new entities using a batch operation.
     *
     * @return The number of rows affected (0 or greater).
     */
    suspend fun insert(entities: List<T>, connection: Connection? = null): Int = withConnection(connection) { conn ->
        if (entities.isEmpty()) return@withConnection 0
        val sql = getInsertSql()
        conn.prepareStatement(sql).use { stmt ->
            for (entity in entities) {
                stmt.bindInsertWith(entity)
                stmt.addBatch()
            }
            stmt.executeBatch().count { it != 0 }
        }
    }

    /**
     * Updates an existing entity.
     *
     * @return The number of rows affected (0 or 1).
     */
    suspend fun update(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        val sql = getUpdateSql()
        conn.prepareStatement(sql).use { stmt ->
            stmt.bindUpdateWith(entity)
            stmt.executeUpdate()
        }
    }

    /**
     * Updates a list of existing entities using a batch operation.
     *
     * @return The number of rows affected (0 or greater).
     */
    suspend fun update(entities: List<T>, connection: Connection? = null): Int = withConnection(connection) { conn ->
        if (entities.isEmpty()) return@withConnection 0
        val sql = getUpdateSql()
        conn.prepareStatement(sql).use { stmt ->
            for (entity in entities) {
                stmt.bindUpdateWith(entity)
                stmt.addBatch()
            }
            stmt.executeBatch().count { it != 0 }
        }
    }

    /**
     * Retrieves an entity by its ID.
     *
     * @return The entity with the specified ID, or null if not found.
     */
    suspend fun findById(id: String, connection: Connection? = null): T? = withConnection(connection) { conn ->
        val sql = "SELECT * FROM $tableName WHERE id = ? LIMIT 1"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    /**
     * Retrieves all entities from the table.
     * This method uses a native SQL query to fetch all records.
     * Use with caution as this operation can be expensive for large tables.
     *
     * @return A list of all entities in the table.
     */
    suspend fun listAll(connection: Connection? = null): List<T> = withConnection(connection) { conn ->
        val sql = "SELECT * FROM $tableName"
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    /**
     * Deletes an entity based on its key columns.
     *
     * @return The number of rows affected (0 or 1).
     */
    suspend fun delete(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        val sql = getDeleteSql()
        conn.prepareStatement(sql).use { stmt ->
            stmt.bindDeleteWith(entity)
            stmt.executeUpdate()
        }
    }

    /**
     * Deletes a list of entities based on their key columns.
     * This method uses batch operations for better performance.
     * The operation is transactional and will be rolled back if any deletion fails.
     *
     * @return The number of rows affected (0 or greater).
     */
    suspend fun delete(entities: List<T>, connection: Connection? = null): Int = withConnection(connection) { conn ->
        if (entities.isEmpty()) return@withConnection 0
        val sql = getDeleteSql()
        conn.prepareStatement(sql).use { stmt ->
            for (entity in entities) {
                stmt.bindDeleteWith(entity)
                stmt.addBatch()
            }
            stmt.executeBatch().count { it != 0 }
        }
    }

    /**
     * Deletes all workflows from the database.
     * This method is transactional and uses a native SQL query to delete all workflows.
     * Use with caution as this operation cannot be undone.
     *
     * @return The number of workflows deleted
     */
    suspend fun deleteAll(connection: Connection? = null): Int = withConnection(connection) { conn ->
        val sql = "DELETE FROM $tableName"
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeUpdate()
        }
    }

    /**
     * Counts the total number of records in the table.
     * This method uses a native SQL query to count all records.
     *
     * @return The total number of records in the table
     */
    suspend fun count(connection: Connection? = null): Long = withConnection(connection) { conn ->
        val sql = "SELECT COUNT(id) FROM $tableName"
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    private fun getUpdateSql(): String {
        val setClause = columns.filterNot { it in (keyColumns + "id") }.joinToString { "${q(it)} = ?" }
        val whereClause = keyColumns.joinToString(separator = " AND ") { "${q(it)} = ?" }

        return "UPDATE $tableName SET $setClause WHERE $whereClause"
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

    private fun getDeleteSql(): String {
        val whereClause = keyColumns.joinToString(separator = " AND ") { "$it = ?" }

        return "DELETE FROM $tableName WHERE $whereClause"
    }

    // Helper that returns the dialect-specific quoting of an SQL identifier.
    private fun q(id: String): String = when (databaseManager.dbType) {
        DB_TYPE_POSTGRESQL -> "\"$id\""     // → "status"
        DB_TYPE_MYSQL -> "`$id`"            // → `status`
        DB_TYPE_IN_MEMORY -> id             // H2: bare identifiers are fine
        else -> id
    }


    /**
     * Executes a block of code with a database connection. If a connection is provided, it uses that connection;
     * otherwise, it acquires a new one from the datasource. This function ensures the appropriate context is utilized
     * for IO operations, and manages the connection lifecycle if a new connection is acquired.
     *
     * @param connection An optional `Connection` instance. If null, a new connection is obtained from the datasource.
     * @param block A lambda function that takes a `Connection` as a parameter and returns a result of type `R`.
     * @return The result of executing the provided block with a `Connection`.
     * @throws SQLException If an error occurs while acquiring or using the connection from the datasource.
     */
    protected suspend fun <R> withConnection(connection: Connection?, block: (Connection) -> R): R =
        withContext(Dispatchers.IO) {
            when (connection) {
                null -> databaseManager.datasource.connection.use { block(it) }
                else -> block(connection)
            }
        }

    protected fun ResultSet.getInstant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
