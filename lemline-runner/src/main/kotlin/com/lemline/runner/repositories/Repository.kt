// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class Repository<T> {

    /**
     * The database manager instance used to access the database.
     */
    protected abstract val databaseManager: DatabaseManager

    /**
     * The name of the database table associated with this repository.
     */
    protected abstract val tableName: String

    /**
     * A map associating column names with functions responsible for binding column values
     * to the respective placeholders in a SQL `PreparedStatement`.
     */
    protected abstract val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit>


    /**
     * A list of column names that are part of the primary keys of the table.
     */
    protected abstract val keyColumns: List<String>

    /**
     * A list of all columns in the table
     */
    private val allColumns by lazy { prepareStatementMap.keys }

    /**
     * A list of columns that are not part of the primary or unique key.
     */
    private val nonKeyColumns by lazy { allColumns.filter { !keyColumns.contains(it) } }


    private fun bindAll(stmt: PreparedStatement, entity: T) {
        allColumns.mapIndexed { index, column -> prepareStatementMap[column]!!(stmt, entity, index + 1) }
    }

    private fun bindNonKeys(stmt: PreparedStatement, entity: T) {
        nonKeyColumns.mapIndexed { index, column -> prepareStatementMap[column]!!(stmt, entity, index + 1) }
    }

    private fun bindKeys(stmt: PreparedStatement, entity: T, startIndex: Int = 0) {
        keyColumns.mapIndexed { index, column -> prepareStatementMap[column]!!(stmt, entity, startIndex + index + 1) }
    }

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
     */
    internal abstract fun createModel(rs: ResultSet): T

    /**
     * Inserts a new entity.
     */
    suspend fun insert(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        conn.prepareStatement(insertSql).use { stmt ->
            bindAll(stmt, entity)
            stmt.executeUpdate()
        }
    }

    private val insertSql by lazy {
        val colsCsv = allColumns.joinToString { q(it) }  // Comma-separated column names, e.g., "id","message",…
        val valsCsv = allColumns.joinToString { "?" }    // Comma-separated placeholders, e.g., ?,?,?

        when (databaseManager.dbType) {
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

    /**
     * Inserts a list of new entities using a batch operation.
     *
     * @return The number of rows affected (0 or greater).
     */
    suspend fun insert(entities: List<T>, connection: Connection? = null): Int = withConnection(connection) { conn ->
        if (entities.isEmpty()) return@withConnection 0
        conn.prepareStatement(insertSql).use { stmt ->
            for (entity in entities) {
                bindAll(stmt, entity)
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
    open suspend fun update(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        conn.prepareStatement(updateSql).use { stmt ->
            bindNonKeys(stmt, entity)
            bindKeys(stmt, entity, nonKeyColumns.size)
            stmt.executeUpdate()
        }
    }

    private val updateSql by lazy {
        val setClause = nonKeyColumns.joinToString { "${q(it)} = ?" }
        val whereClause = keyColumns.joinToString(separator = " AND ") { "${q(it)} = ?" }

        "UPDATE $tableName SET $setClause WHERE $whereClause"
    }

    /**
     * Updates a list of existing entities using a batch operation.
     *
     * @return The number of rows affected (0 or greater).
     */
    suspend fun update(entities: List<T>, connection: Connection? = null): Int = withConnection(connection) { conn ->
        if (entities.isEmpty()) return@withConnection 0
        conn.prepareStatement(updateSql).use { stmt ->
            for (entity in entities) {
                bindNonKeys(stmt, entity)
                bindKeys(stmt, entity, nonKeyColumns.size)
                stmt.addBatch()
            }
            stmt.executeBatch().count { it != 0 }
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
        conn.prepareStatement(listAllSQL).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val listAllSQL by lazy { "SELECT * FROM $tableName" }

    /**
     * Deletes an entity based on its key columns.
     *
     * @return The number of rows affected (0 or 1).
     */
    suspend fun delete(entity: T, connection: Connection? = null): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteSql).use { stmt ->
            bindKeys(stmt, entity)
            stmt.executeUpdate()
        }
    }

    private val deleteSql: String by lazy {
        val whereClause = keyColumns.joinToString(separator = " AND ") { "$it = ?" }

        "DELETE FROM $tableName WHERE $whereClause"
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
        conn.prepareStatement(deleteSql).use { stmt ->
            for (entity in entities) {
                bindKeys(stmt, entity)
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
        conn.prepareStatement(deleteAllSql).use { stmt ->
            stmt.executeUpdate()
        }
    }

    private val deleteAllSql by lazy { "DELETE FROM $tableName" }

    /**
     * Counts the total number of records in the table.
     * This method uses a native SQL query to count all records.
     *
     * @return The total number of records in the table
     */
    suspend fun count(connection: Connection? = null): Long = withConnection(connection) { conn ->
        conn.prepareStatement(countSql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    private val countSql by lazy { "SELECT COUNT(*) FROM $tableName" }

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
     * @throws java.sql.SQLException If an error occurs while acquiring or using the connection from the datasource.
     */
    protected suspend fun <R> withConnection(connection: Connection?, block: (Connection) -> R): R =
        withContext(Dispatchers.IO) {
            when (connection) {
                null -> databaseManager.datasource.connection.use { block(it) }
                else -> block(connection)
            }
        }

    @ExperimentalTime
    protected fun ResultSet.getInstant(col: String): Instant? = getTimestamp(col)?.toInstant()?.toKotlinInstant()
}
