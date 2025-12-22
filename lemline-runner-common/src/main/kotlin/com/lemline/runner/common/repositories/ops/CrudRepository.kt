// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.common.repositories.ops

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.common.config.DatabaseConstants.DB_TYPE_MYSQL
import com.lemline.runner.common.config.DatabaseConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.IdV7Helper
import com.lemline.runner.common.repositories.with.WithCrudRepository
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val CREATED_AT_COLUMN = "created_at"
const val UPDATED_AT_COLUMN = "updated_at"

/**
 * Base repository class providing core CRUD operations.
 * Uses composition via [com.lemline.runner.common.repositories.helpers.ColumnBindings] for column configuration instead of inheritance.
 *
 * @param T The entity type managed by this repository
 */
abstract class CrudRepository<T> : WithCrudRepository<T> {

    /**
     * The database config instance used to access the database.
     */
    abstract val databaseConfig: DatabaseConfig

    /**
     * The name of the database table associated with this repository.
     */
    protected abstract val tableName: String

    /**
     * Column bindings that define how entity properties map to database columns.
     */
    protected abstract val columns: ColumnBindings<T>

    /**
     * Creates a model instance from a ResultSet.
     */
    protected abstract fun createModel(rs: ResultSet): T

    /**
     * Helper for IDV7 operations, initialized lazily based on database type.
     */
    protected val idHelper: IdV7Helper by lazy { IdV7Helper(databaseConfig.dbType) }

    /**
     * Shorthand for setting IDV7 values in prepared statements.
     */
    protected val setIDV7 get() = idHelper.set

    /**
     * Shorthand for getting IDV7 values from result sets.
     */
    protected val getIDV7 get() = idHelper.get

    /**
     * All columns including auto-managed timestamp columns.
     */
    private val allColumns by lazy { columns.allColumns + CREATED_AT_COLUMN + UPDATED_AT_COLUMN }

    /**
     * Columns that can be updated (excludes key columns and created_at).
     */
    private val updatableColumns by lazy {
        allColumns.filter { !columns.keyColumns.contains(it) && it != CREATED_AT_COLUMN }
    }

    private fun bindInsert(stmt: PreparedStatement, entity: T) {
        allColumns.forEachIndexed { index, column ->
            when {
                columns.bindings.containsKey(column) -> columns.bindings[column]!!.bind(stmt, entity, index + 1)
                column == CREATED_AT_COLUMN -> stmt.setTimestamp(index + 1, Timestamp.from(Instant.now()))
                column == UPDATED_AT_COLUMN -> stmt.setNull(index + 1, Types.TIMESTAMP)
                else -> error("No handler for column $column")
            }
        }
    }

    private fun bindUpdate(stmt: PreparedStatement, entity: T) {
        updatableColumns.forEachIndexed { index, column ->
            when (column) {
                UPDATED_AT_COLUMN -> stmt.setTimestamp(index + 1, Timestamp.from(Instant.now()))
                else -> columns.bindings[column]!!.bind(stmt, entity, index + 1)
            }
        }
    }

    private fun bindKeys(stmt: PreparedStatement, entity: T, startIndex: Int = 0) {
        columns.keyColumns.forEachIndexed { index, column ->
            columns.bindings[column]!!.bind(stmt, entity, startIndex + index + 1)
        }
    }


    /**
     * Inserts a new entity.
     * Uses ON CONFLICT DO NOTHING (PostgreSQL) or INSERT IGNORE (MySQL) for idempotency.
     * For H2: catches unique constraint violations and returns 0.
     */
    override suspend fun insert(entity: T, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
            try {
                conn.prepareStatement(insertSql).use { stmt ->
                    bindInsert(stmt, entity)
                    stmt.executeUpdate()
                }
            } catch (e: java.sql.SQLIntegrityConstraintViolationException) {
                // H2 throws this for duplicate key violations - treat as "already exists"
                0
            }
        }

    private val insertSql by lazy {
        val colsCsv = allColumns.joinToString { q(it) }
        val valsCsv = allColumns.joinToString { "?" }

        when (databaseConfig.dbType) {
            DB_TYPE_POSTGRESQL -> """
                INSERT INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
                ON CONFLICT DO NOTHING
            """.trimIndent()

            DB_TYPE_IN_MEMORY -> """
                INSERT INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
            """.trimIndent()

            DB_TYPE_MYSQL -> """
                INSERT IGNORE INTO $tableName ($colsCsv)
                VALUES ($valsCsv)
            """.trimIndent()

            else -> error("Unsupported database type '${databaseConfig.dbType}'")
        }
    }

    /**
     * Inserts a list of new entities using a batch operation.
     * For H2: catches unique constraint violations for individual entries.
     */
    override suspend fun insert(entities: List<T>, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
            if (entities.isEmpty()) return@withConnection 0

            // For H2, use individual inserts to handle constraint violations per entity
            if (databaseConfig.dbType == DB_TYPE_IN_MEMORY) {
                var successCount = 0
                conn.prepareStatement(insertSql).use { stmt ->
                    for (entity in entities) {
                        try {
                            bindInsert(stmt, entity)
                            successCount += stmt.executeUpdate()
                        } catch (e: java.sql.SQLIntegrityConstraintViolationException) {
                            // Skip duplicates
                        }
                    }
                }
                return@withConnection successCount
            }

            // For PostgreSQL/MySQL, use batch operations
            conn.prepareStatement(insertSql).use { stmt ->
                for (entity in entities) {
                    bindInsert(stmt, entity)
                    stmt.addBatch()
                }
                stmt.executeBatch().count { it != 0 }
            }
        }

    /**
     * Updates an existing entity.
     */
    override suspend fun update(entity: T, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(updateSql).use { stmt ->
                bindUpdate(stmt, entity)
                bindKeys(stmt, entity, updatableColumns.size)
                stmt.executeUpdate()
            }
        }

    private val updateSql by lazy {
        val setClause = updatableColumns.joinToString { "${q(it)} = ?" }
        val whereClause = columns.keyColumns.joinToString(separator = " AND ") { "${q(it)} = ?" }
        "UPDATE $tableName SET $setClause WHERE $whereClause"
    }

    /**
     * Updates a list of existing entities using a batch operation.
     */
    override suspend fun update(entities: List<T>, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
            if (entities.isEmpty()) return@withConnection 0
            conn.prepareStatement(updateSql).use { stmt ->
                for (entity in entities) {
                    bindUpdate(stmt, entity)
                    bindKeys(stmt, entity, updatableColumns.size)
                    stmt.addBatch()
                }
                stmt.executeBatch().count { it != 0 }
            }
        }

    /**
     * Retrieves all entities from the table.
     */
    override suspend fun listAll(connection: Connection?): List<T> = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(listAllSQL).use { stmt ->
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val listAllSQL by lazy { "SELECT * FROM $tableName" }

    /**
     * Deletes an entity based on its key columns.
     */
    override suspend fun delete(entity: T, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(deleteSql).use { stmt ->
                bindKeys(stmt, entity)
                stmt.executeUpdate()
            }
        }

    private val deleteSql: String by lazy {
        val whereClause = columns.keyColumns.joinToString(separator = " AND ") { "$it = ?" }
        "DELETE FROM $tableName WHERE $whereClause"
    }

    /**
     * Deletes a list of entities based on their key columns.
     */
    override suspend fun delete(entities: List<T>, connection: Connection?): Int =
        databaseConfig.withConnection(connection) { conn ->
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
     * Deletes all entities from the table.
     */
    override suspend fun deleteAll(connection: Connection?): Int = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(deleteAllSql).use { stmt ->
            stmt.executeUpdate()
        }
    }

    private val deleteAllSql by lazy { "DELETE FROM $tableName" }

    /**
     * Counts the total number of records in the table.
     */
    override suspend fun countAll(connection: Connection?): Int = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(countSql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private val countSql by lazy { "SELECT COUNT(*) FROM $tableName" }

    /**
     * Returns the dialect-specific quoting of an SQL identifier.
     */
    protected fun q(id: String): String = when (databaseConfig.dbType) {
        DB_TYPE_POSTGRESQL -> "\"$id\""
        DB_TYPE_MYSQL -> "`$id`"
        DB_TYPE_IN_MEMORY -> id
        else -> id
    }

    /**
     * Converts a ResultSet to a list of models.
     */
    protected fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}

/**
 * Gets an Instant from a ResultSet column.
 */
fun ResultSet.getInstant(col: String): kotlin.time.Instant? =
    getTimestamp(col)?.toInstant()?.toKotlinInstant()
