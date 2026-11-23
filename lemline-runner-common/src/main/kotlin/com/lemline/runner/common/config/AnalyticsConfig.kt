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

    suspend fun <R> withConnection(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    suspend fun <R> withTransaction(
        connection: Connection? = null,
        block: suspend (Connection) -> R
    ): R

    fun insertIgnoreInto(tableName: String, columns: String, values: String): String {
        val conflictTarget = when (analyticsType) {
            AnalyticsType.H2, AnalyticsType.POSTGRESQL -> ""
            AnalyticsType.SQLITE -> " (source, event_id)"
        }
        return "INSERT INTO $tableName ($columns) VALUES ($values) ON CONFLICT$conflictTarget DO NOTHING"
    }
}
