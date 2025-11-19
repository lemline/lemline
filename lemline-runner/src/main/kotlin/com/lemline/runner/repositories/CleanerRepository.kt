// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.AwaitingCompletionModel
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract repository class for managing cleanable entities that track completion for cleanup.
 *
 * This class extends `WithInstanceRepository` to provide additional operations for
 * entities that implement the `CleanableModel` interface. It adds support for cleanup
 * tracking via completion timestamp (outbox_completed_at).
 *
 * Key Features:
 * - Defines SQL column mappings for cleanup-related fields
 * - Provides methods for finding entities ready for cleanup
 * - Maps cleanup-specific fields using prepared SQL statements
 *
 * Generic Type:
 * - `T`: A type parameter extending `CleanableModel`, representing the type of entity this repository will handle.
 *
 * @see AwaitingCompletionModel for the base entity interface
 * @see WithInstanceRepository for instance-related functionality
 */
@ExperimentalSerializationApi
@ExperimentalTime
abstract class CleanerRepository<T : AwaitingCompletionModel> : WithInstanceRepository<T>() {

    companion object Companion {
        /**
         * Column name for completion timestamp
         */
        internal const val OUTBOX_COMPLETED_AT_COLUMN = "outbox_completed_at"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            OUTBOX_COMPLETED_AT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
                entity.outboxCompletedAt?.let {
                    stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
                } ?: stmt.setNull(idx, java.sql.Types.TIMESTAMP)
            }
        )
    }

    /**
     * Helper method to get outbox completed at timestamp from ResultSet.
     */
    protected fun getOutboxCompletedAt(rs: java.sql.ResultSet): Instant? {
        val timestamp = rs.getTimestamp(OUTBOX_COMPLETED_AT_COLUMN)
        return timestamp?.toInstant()?.toKotlinInstant()
    }

    /**
     * Find entities that have been completed and are older than the specified cutoff date,
     * making them ready for cleanup.
     *
     * @param cutoffDate The cutoff date - entities completed before this date will be returned
     * @param batchSize Maximum number of entities to return
     * @param connection An optional database connection to use
     * @return List of entities ready for cleanup
     */
    open suspend fun findEntitiesToDelete(
        cutoffDate: Instant,
        batchSize: Int,
        connection: Connection?
    ): List<T> = withConnection(connection) { conn ->
        conn.prepareStatement(findEntitiesToDeleteSql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(cutoffDate.toJavaInstant()))
            stmt.setInt(2, batchSize)
            stmt.executeQuery().use { it.toModels() }
        }
    }

    private val findEntitiesToDeleteSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
          AND $OUTBOX_COMPLETED_AT_COLUMN < ?
        LIMIT ?
        """.trimIndent()
    }
}
