// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.helpers

import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.DatabaseType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.*

/**
 * Helper class for database-agnostic IDV7 handling.
 * Provides functions to set and get IDV7 values in PreparedStatements and ResultSets,
 * handling the different storage formats across databases (native UUID for PostgreSQL/H2,
 * binary for MySQL).
 *
 * @param dbType The database type
 */
class IdV7Helper(private val dbType: DatabaseType) {

    /**
     * Sets an IDV7 value in a PreparedStatement at the given index.
     * Handles database-specific UUID encoding.
     */
    val set: (PreparedStatement, Int, IDV7?) -> Unit = when (dbType) {
        DatabaseType.H2, DatabaseType.POSTGRESQL -> { stmt, parameterIndex, id ->
            when (id) {
                null -> stmt.setNull(parameterIndex, Types.OTHER)
                else -> stmt.setObject(parameterIndex, id.value)
            }
        }

        DatabaseType.MYSQL -> { stmt, parameterIndex, id ->
            when (id) {
                null -> stmt.setNull(parameterIndex, Types.BINARY)
                else -> stmt.setBytes(parameterIndex, id.toBytes())
            }
        }
    }

    /**
     * Gets an IDV7 value from a ResultSet by column name.
     * Handles database-specific UUID decoding.
     */
    val get: (ResultSet, String) -> IDV7? = when (dbType) {
        DatabaseType.H2, DatabaseType.POSTGRESQL -> { rs, columnName ->
            rs.getObject(columnName, UUID::class.java)?.let { IDV7(it) }
        }

        DatabaseType.MYSQL -> { rs, columnName ->
            rs.getBytes(columnName)?.let { IDV7.from(it) }
        }
    }
}
