// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.helpers

import com.lemline.common.values.IDV7
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
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
 * @param dbType The database type identifier
 */
class IdV7Helper(private val dbType: String) {

    /**
     * Sets an IDV7 value in a PreparedStatement at the given index.
     * Handles database-specific UUID encoding.
     */
    val set: (PreparedStatement, Int, IDV7?) -> Unit = when (dbType) {
        DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { stmt, parameterIndex, id ->
            when (id) {
                null -> stmt.setNull(parameterIndex, Types.OTHER)
                else -> stmt.setObject(parameterIndex, id.value)
            }
        }

        DB_TYPE_MYSQL -> { stmt, parameterIndex, id ->
            when (id) {
                null -> stmt.setNull(parameterIndex, Types.BINARY)
                else -> stmt.setBytes(parameterIndex, id.toBytes())
            }
        }

        else -> error("Unsupported database type '$dbType'")
    }

    /**
     * Gets an IDV7 value from a ResultSet by column name.
     * Handles database-specific UUID decoding.
     */
    val get: (ResultSet, String) -> IDV7? = when (dbType) {
        DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { rs, columnName ->
            rs.getObject(columnName, UUID::class.java)?.let { IDV7(it) }
        }

        DB_TYPE_MYSQL -> { rs, columnName ->
            rs.getBytes(columnName)?.let { IDV7.from(it) }
        }

        else -> error("Unsupported database type '$dbType'")
    }
}
