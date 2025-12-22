// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.common.repositories.helpers

import com.lemline.runner.common.models.WithCompletedAt
import com.lemline.runner.common.repositories.ops.getInstant
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Constants and helpers for completion-related database operations.
 */
object CompletionOps {
    const val COMPLETED_AT_COLUMN = "completed_at"
}

/**
 * Extension function to add completion column to ColumnBindingsBuilder.
 */
fun <T : WithCompletedAt> ColumnBindingsBuilder<T>.completionColumns() {
    column(CompletionOps.COMPLETED_AT_COLUMN) { stmt, entity, idx ->
        entity.completedAt?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
}

/**
 * Extension function to read completion field from a ResultSet into a WithCompletion entity.
 */
fun <T : WithCompletedAt> T.readCompletionField(rs: ResultSet): T = apply {
    completedAt = rs.getInstant(CompletionOps.COMPLETED_AT_COLUMN)
}
