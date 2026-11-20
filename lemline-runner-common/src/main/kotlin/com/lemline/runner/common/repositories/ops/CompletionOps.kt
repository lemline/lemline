// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.ops

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.models.WithCompletedAt
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.toJavaInstant

/**
 * Constants and helpers for completion-related database operations.
 */
object CompletionOps {
    const val COMPLETED_AT_COLUMN = "completed_at"
}

/**
 * Extension function to add completion column to ColumnBindingsBuilder.
 */
fun <T : WithCompletedAt> ColumnBindingsBuilder<T>.completionColumns(databaseConfig: DatabaseConfig) {
    column(CompletionOps.COMPLETED_AT_COLUMN) { stmt, entity, idx ->
        entity.completedAt?.let {
            databaseConfig.bindTimestamp(stmt, idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
}

/**
 * Extension function to read completion field from a ResultSet into a WithCompletion entity.
 */
fun <T : WithCompletedAt> T.readCompletionField(rs: ResultSet): T = apply {
    completedAt = rs.getInstant(CompletionOps.COMPLETED_AT_COLUMN)
}
