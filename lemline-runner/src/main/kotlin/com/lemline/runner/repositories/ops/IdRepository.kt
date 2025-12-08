// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.ops

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.WithId
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.helpers.IdV7Helper
import com.lemline.runner.repositories.with.WithIdRepository
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val ID_COLUMN = "id"

/**
 * Helper class providing ID-based database operations.
 * Use this via composition instead of inheriting from WithIdRepository.
 *
 * @param T The entity type, must implement [WithId]
 * @param tableName The database table name
 * @param idHelper The IDV7 helper for database-agnostic ID handling
 * @param createModel Function to create a model from a ResultSet
 * @param databaseManager The database manager for connections
 */
class IdRepository<T : WithId>(
    private val tableName: String,
    private val idHelper: IdV7Helper,
    private val createModel: (ResultSet) -> T,
    private val databaseManager: DatabaseManager
) : WithIdRepository<T> {
    /**
     * Finds an entity by its unique identifier.
     *
     * @param id The unique identifier of the entity to find
     * @param connection Optional database connection to use
     * @return The entity corresponding to the provided identifier, or null if not found
     */
    override suspend fun findById(id: IDV7, connection: Connection?): T? =
        databaseManager.withConnection(connection) { conn ->
            conn.prepareStatement(findByIdSql).use { stmt ->
                idHelper.set(stmt, 1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByIdSql by lazy { "SELECT * FROM $tableName WHERE $ID_COLUMN = ? LIMIT 1" }

    /**
     * Deletes an entity by its unique ID.
     *
     * @param id The unique identifier of the entity to delete
     * @param connection Optional database connection to use
     * @return The number of rows affected by the delete operation
     */
    override suspend fun deleteById(id: IDV7, connection: Connection?): Int =
        databaseManager.withConnection(connection) { conn ->
            conn.prepareStatement(deleteByIdSql).use { stmt ->
                idHelper.set(stmt, 1, id)
                stmt.executeUpdate()
            }
        }

    private val deleteByIdSql by lazy { "DELETE FROM $tableName WHERE $ID_COLUMN = ?" }

}

/**
 * Extension function to add the ID key column to ColumnBindingsBuilder.
 * Adds the 'id' column as a primary key for entities implementing [WithId].
 *
 * @param idHelper The IDV7 helper for database-agnostic ID handling
 *
 * Usage:
 * ```kotlin
 * override val columns by lazy {
 *     ColumnBindingsBuilder<MyModel>().apply {
 *         idColumn(idHelper)           // adds 'id' as key column
 *         instanceColumns(idHelper)    // adds instance columns
 *         // ... other columns
 *     }.build()
 * }
 * ```
 */
fun <T : WithId> ColumnBindingsBuilder<T>.idColumn(idHelper: IdV7Helper) {
    key(ID_COLUMN) { stmt, entity, idx -> idHelper.set(stmt, idx, entity.id) }
}
