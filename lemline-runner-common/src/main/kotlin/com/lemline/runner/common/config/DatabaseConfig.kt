// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import java.sql.Connection

/**
 * Interface for database access operations.
 * Provides methods for executing database operations with proper connection management.
 *
 * Implementations handle:
 * - Connection pooling and lifecycle
 * - Transaction management
 * - Database-specific dialect operations
 */
interface DatabaseConfig {
    /**
     * The database type for this configuration.
     */
    val dbType: DatabaseType

    /**
     * Executes a block of code with a database connection.
     * If an existing connection is provided, it will be reused.
     * Otherwise, a new connection is obtained from the pool and closed after use.
     *
     * @param connection Optional existing connection to reuse
     * @param block The code block to execute with the connection
     * @return The result of the block execution
     */
    suspend fun <R> withConnection(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    /**
     * Executes a block of code within a database transaction.
     * If an existing connection is provided, it will be reused (assuming it's already in a transaction).
     * Otherwise, a new connection is obtained, auto-commit is disabled, and the transaction
     * is committed on success or rolled back on failure.
     *
     * @param connection Optional existing connection to reuse (must already be in transaction mode)
     * @param block The code block to execute within the transaction
     * @return The result of the block execution
     * @throws Throwable Rethrows any exception after rolling back the transaction
     */
    suspend fun <R> withTransaction(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    // ========================================
    // SQL Dialect Methods
    // ========================================

    /**
     * Returns the database-specific JSON array aggregation function.
     *
     * - PostgreSQL: json_agg(column::json)
     * - MySQL: JSON_ARRAYAGG(CAST(column AS JSON))
     * - H2: JSON_ARRAYAGG(column FORMAT JSON)
     *
     * @param column The column expression containing JSON strings to aggregate
     * @return SQL fragment for JSON array aggregation
     */
    fun jsonArrayAgg(column: String): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "json_agg($column::json)"
        DatabaseType.MYSQL -> "JSON_ARRAYAGG(CAST($column AS JSON))"
        DatabaseType.H2 -> "JSON_ARRAYAGG($column FORMAT JSON)"
    }

    /**
     * Returns the database-specific JSON array aggregation function with ordering.
     *
     * - PostgreSQL: json_agg(column::json ORDER BY orderColumn)
     * - MySQL: Uses GROUP_CONCAT with ORDER BY, wrapped in JSON_ARRAY parsing
     *          Note: MySQL's JSON_ARRAYAGG doesn't support ORDER BY inside the function
     * - H2: JSON_ARRAYAGG(column FORMAT JSON ORDER BY orderColumn)
     *
     * IMPORTANT: For MySQL, the result needs special parsing as it returns a comma-separated
     * string that must be wrapped in JSON array format.
     *
     * @param column The column expression containing JSON strings to aggregate
     * @param orderColumn The column to order by
     * @return SQL fragment for ordered JSON array aggregation
     */
    fun jsonArrayAggOrdered(column: String, orderColumn: String): String = when (dbType) {
        DatabaseType.POSTGRESQL -> "json_agg($column::json ORDER BY $orderColumn)"
        // MySQL: JSON_ARRAYAGG doesn't support ORDER BY, use subquery instead
        DatabaseType.MYSQL -> "JSON_ARRAYAGG(sub.v)"
        DatabaseType.H2 -> "JSON_ARRAYAGG($column FORMAT JSON ORDER BY $orderColumn)"
    }

    /**
     * Generates a MySQL-compatible subquery for ordered JSON aggregation.
     * This is needed because MySQL's JSON_ARRAYAGG doesn't support ORDER BY.
     *
     * For MySQL, wrap the JSON_ARRAYAGG like:
     * ```sql
     * (SELECT JSON_ARRAYAGG(sub.v) FROM (SELECT col AS v FROM table e WHERE ... ORDER BY order_col) sub)
     * ```
     *
     * @param table The table to select from
     * @param tableAlias The alias for the table (used in column references)
     * @param column The column to aggregate (should use the tableAlias, e.g., "e.event")
     * @param orderColumn The column to order by (should use the tableAlias, e.g., "e.created_at")
     * @param whereClause The WHERE clause (without WHERE keyword, should use tableAlias)
     * @return Full subquery SQL for MySQL, or just the aggregation for other databases
     */
    fun jsonArrayAggOrderedSubquery(
        table: String,
        tableAlias: String,
        column: String,
        orderColumn: String,
        whereClause: String
    ): String = when (dbType) {
        DatabaseType.POSTGRESQL ->
            "(SELECT json_agg($column::json ORDER BY $orderColumn) FROM $table $tableAlias WHERE $whereClause)"
        DatabaseType.MYSQL ->
            "(SELECT JSON_ARRAYAGG(sub.v) FROM (SELECT $column AS v FROM $table $tableAlias WHERE $whereClause ORDER BY $orderColumn) sub)"
        DatabaseType.H2 ->
            "(SELECT JSON_ARRAYAGG($column FORMAT JSON ORDER BY $orderColumn) FROM $table $tableAlias WHERE $whereClause)"
    }

    /**
     * Returns the database-specific INSERT ON CONFLICT/IGNORE syntax for INSERT...SELECT.
     *
     * - PostgreSQL/H2: INSERT ... ON CONFLICT DO NOTHING
     * - MySQL: INSERT IGNORE INTO ...
     *
     * @param tableName The table name
     * @param columns The columns to insert
     * @param selectSql The SELECT SQL to insert from
     * @return Full INSERT...SELECT SQL with conflict handling
     */
    fun insertIgnoreSelect(tableName: String, columns: String, selectSql: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) $selectSql"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) $selectSql ON CONFLICT DO NOTHING"
    }

    /**
     * Returns the database-specific INSERT ON CONFLICT/IGNORE syntax for INSERT...VALUES.
     *
     * - PostgreSQL/H2: INSERT INTO ... VALUES (...) ON CONFLICT DO NOTHING
     * - MySQL: INSERT IGNORE INTO ... VALUES (...)
     *
     * @param tableName The table name
     * @param columns The columns to insert
     * @param values The VALUES clause (with placeholders)
     * @return Full INSERT...VALUES SQL with conflict handling
     */
    fun insertIgnoreInto(tableName: String, columns: String, values: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) VALUES ($values)"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) VALUES ($values) ON CONFLICT DO NOTHING"
    }
}
