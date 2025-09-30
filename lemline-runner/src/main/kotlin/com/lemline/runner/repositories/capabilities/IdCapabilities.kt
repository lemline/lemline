// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.capabilities

import com.lemline.common.values.IDV7
import com.lemline.runner.repositories.bases.Repository
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

internal const val ID_COLUMN = "id"

/**
 * Represents a generic model that includes a unique identifier.
 */
interface IdColumn {
    val id: IDV7
}

/**
 * Capabilities of [IdColumn]
 */
interface IdCapabilities<T : IdColumn> {
    /**
     * Finds an entity by its unique identifier.
     *
     * @param id The unique identifier of the entity to look for.
     * @param connection An optional database connection to use for the query. If not provided, a new connection may be used.
     * @return The entity corresponding to the given identifier, or null if no matching entity is found.
     */
    suspend fun findById(id: IDV7, connection: Connection? = null): T?

    /**
     * Deletes an entity identified by its unique identifier.
     *
     * @param id The unique identifier of the entity to be deleted.
     * @param connection An optional database connection to use for the operation. If not provided, a new connection may be used.
     * @return The number of rows affected by the delete operation. Typically, 1 if the entity was successfully deleted, or 0 if no matching entity was found.
     */
    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int
}

/**
 * Implementation of capabilities of [IdColumn]
 */
class IdCapable<T : IdColumn>(
    private val repository: Repository<T>
) : IdCapabilities<T> {

    val ResultSet.id get() = repository.getIDV7(this, ID_COLUMN)!!

    val mapping: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                repository.setIDV7(stmt, idx, entity.id)
            }
        )
    }

    override suspend fun findById(id: IDV7, connection: Connection?): T? =
        repository.withConnection(connection) { conn ->
            conn.prepareStatement(findByIdSql).use { stmt ->
                repository.setIDV7(stmt, 1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) repository.createModel(rs) else null
                }
            }
        }

    private val findByIdSql by lazy { "SELECT * FROM ${repository.tableName} WHERE $ID_COLUMN = ? LIMIT 1" }

    override suspend fun deleteById(id: IDV7, connection: Connection?): Int =
        repository.withConnection(connection) { conn ->
            conn.prepareStatement(deleteByIdSql).use { stmt ->
                repository.setIDV7(stmt, 1, id)
                stmt.executeUpdate()
            }
        }

    private val deleteByIdSql by lazy { "DELETE FROM ${repository.tableName} WHERE $ID_COLUMN = ?" }
}
