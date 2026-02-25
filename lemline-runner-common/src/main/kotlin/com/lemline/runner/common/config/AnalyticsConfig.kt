// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import java.sql.Connection

/**
 * Analytics-specific database access configuration.
 *
 * This mirrors selected operations from [DatabaseConfig] but keeps analytics storage selection isolated
 * from the main runtime database selection.
 */
interface AnalyticsConfig {
    /**
     * The configured analytics backend type.
     */
    val analyticsType: AnalyticsType

    /**
     * Maps analytics backend type to the shared SQL dialect type.
     */
    val dbType: DatabaseType
        get() = when (analyticsType) {
            AnalyticsType.POSTGRESQL -> DatabaseType.POSTGRESQL
            AnalyticsType.H2 -> DatabaseType.H2
        }

    suspend fun <R> withConnection(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    suspend fun <R> withTransaction(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    fun insertIgnoreSelect(tableName: String, columns: String, selectSql: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) $selectSql"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) $selectSql ON CONFLICT DO NOTHING"
    }

    fun insertIgnoreInto(tableName: String, columns: String, values: String): String = when (dbType) {
        DatabaseType.MYSQL -> "INSERT IGNORE INTO $tableName ($columns) VALUES ($values)"
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> "INSERT INTO $tableName ($columns) VALUES ($values) ON CONFLICT DO NOTHING"
    }
}
