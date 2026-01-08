// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.common.repositories.ops

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

const val CLEANUP_AFTER_COLUMN = "cleanup_after"

/**
 * Helper class providing cleanup-related database operations.
 * Use this via composition instead of inheriting from CleanerRepository.
 *
 * @param T The entity type, must implement [WithCleanup]
 * @param tableName The database table name
 * @param createModel Function to create a model from a ResultSet
 * @param databaseConfig The database config for connections
 */
class CleanerRepository<T : WithCleanup>(
    private val tableName: String,
    private val createModel: (ResultSet) -> T,
    private val databaseConfig: DatabaseConfig
) : WithCleanerRepository<T> {

    /**
     * Find entities that are ready for cleanup (cleanup_after < cutoffDate).
     *
     * The cutoffDate parameter allows the caller to apply a retention offset.
     * For example, to retain entities for 7 days after completion:
     * `findEntitiesToDelete(Clock.System.now() - 7.days, batchSize)`
     *
     * @param cutoffDate The cutoff timestamp - entities with cleanup_after before this are eligible
     * @param batchSize Maximum number of entities to return
     * @param connection An optional database connection to use
     * @return List of entities ready for cleanup
     */
    override suspend fun findEntitiesToDelete(
        cutoffDate: Instant,
        batchSize: Int,
        connection: Connection?
    ): List<T> = databaseConfig.withConnection(connection) { conn ->
        conn.prepareStatement(findEntitiesToDeleteSql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(cutoffDate.toJavaInstant()))
            stmt.setInt(2, batchSize)
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val findEntitiesToDeleteSql by lazy {
        """
            SELECT * FROM $tableName
            WHERE $CLEANUP_AFTER_COLUMN IS NOT NULL
              AND $CLEANUP_AFTER_COLUMN < ?
            ORDER BY $CLEANUP_AFTER_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }

    private fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(createModel(this@toModels))
        }
    }
}

/**
 * Extension function to read cleanup field from a ResultSet into a WithCleanup entity.
 */
fun <T : WithCleanup> T.readCleanupField(rs: ResultSet): T =
    apply {
        cleanupAfter = rs.getInstant(CLEANUP_AFTER_COLUMN)
    }

/**
 * Extension function to add cleaner columns to ColumnBindingsBuilder.
 * Adds the cleanup_after column binding for entities implementing WithCleanup.
 *
 * Usage:
 * ```kotlin
 * override val columns by lazy {
 *     ColumnBindingsBuilder<MyModel>().apply {
 *         key("id") { stmt, entity, idx -> setIDV7(stmt, idx, entity.id) }
 *         cleanupColumns()  // adds cleanup_after
 *         // ... other columns
 *     }.build()
 * }
 * ```
 */
fun <T : WithCleanup> ColumnBindingsBuilder<T>.cleanupColumns() {
    column(CLEANUP_AFTER_COLUMN) { stmt, entity, idx ->
        entity.cleanupAfter?.let {
            stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant()))
        } ?: stmt.setNull(idx, Types.TIMESTAMP)
    }
}
