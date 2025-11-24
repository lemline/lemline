// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.models.WithId
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.time.ExperimentalTime

/**
 * Base class for repositories with a single IDV7 primary key.
 *
 * Provides common operations for entities implementing [WithId]:
 * - [findById] - Find entity by its unique identifier
 * - [deleteById] - Delete entity by its unique identifier
 *
 * IDV7 helpers ([setIDV7], [getIDV7]) are inherited from [Repository].
 *
 * @see WithId for the entity interface
 * @see Repository for base repository functionality
 */
@OptIn(ExperimentalTime::class)
abstract class WithIdRepository<T : WithId> : Repository<T>() {

    companion object {
        internal const val ID_COLUMN = "id"
    }

    override val keyColumns: List<String> = listOf(ID_COLUMN)

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int -> setIDV7(stmt, idx, entity.id) },
        )
    }

    /**
     * Finds an entity by its unique identifier.
     *
     * @param id The unique identifier of the entity to find.
     * @param connection An optional database connection to use. If null, a new connection is acquired.
     * @return The entity corresponding to the provided identifier, or null if no entity is found.
     */
    suspend fun findById(id: IDV7, connection: Connection? = null): T? = withConnection(connection) { conn ->
        conn.prepareStatement(findByIdSql).use { stmt ->
            setIDV7(stmt, 1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByIdSql by lazy { "SELECT * FROM $tableName WHERE $ID_COLUMN = ? LIMIT 1" }

    /**
     * Deletes an entity by its unique ID.
     *
     * @param id The unique identifier of the entity to delete.
     * @param connection An optional database connection to use. If null, a new connection is acquired.
     * @return The number of rows affected by the delete operation.
     */
    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByIdSql).use { stmt ->
            setIDV7(stmt, 1, id)
            stmt.executeUpdate()
        }
    }

    private val deleteByIdSql: String by lazy { "DELETE FROM $tableName WHERE $ID_COLUMN = ?" }
}
