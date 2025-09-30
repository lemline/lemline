// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.capabilities

import com.lemline.runner.models.bases.CleanerColumns
import com.lemline.runner.models.bases.CleanerColumnsBase
import com.lemline.runner.models.bases.OptionalCleanerColumns
import com.lemline.runner.models.bases.runAt
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.outbox.bases.RunStatus.DONE
import com.lemline.runner.repositories.bases.Repository
import com.lemline.runner.repositories.bases.getInstant
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.ExperimentalSerializationApi

internal const val RUN_STATUS_COLUMN = "run_status"
internal const val RUN_AT_COLUMN = "run_at"

/**
 * Capabilities of [CleanerColumns] and [OptionalCleanerColumns]
 */
interface CleanerCapabilities<T : CleanerColumnsBase> {
    /**
     * Finds a list of entities that should be deleted based on a cutoff date and limit.
     *
     * @param cutoffDate The date before which entities should be considered for deletion.
     * @param limit The maximum number of entities to find.
     * @param connection An optional database connection to use for the operation. If not provided, a new connection may be used.
     * @return A list of entities to be deleted, filtered by the specified cutoff date and limit.
     */
    suspend fun findEntitiesToDelete(cutoffDate: Instant, limit: Int, connection: Connection? = null): List<T>
}

/**
 * Implementation of capabilities of [CleanerColumns]
 */
class CleanerCapable<T : CleanerColumns>(repository: Repository<T>) :
    BaseCleanerCapable<T>(repository) {

    val ResultSet.runAt: Instant get() = getInstant(RUN_AT_COLUMN)!!
}

/**
 * Implementation of capabilities of [OptionalCleanerColumns]
 */
class OptionalCleanerCapable<T : OptionalCleanerColumns>(repository: Repository<T>) :
    BaseCleanerCapable<T>(repository) {

    val ResultSet.runAt: Instant? get() = getInstant(RUN_AT_COLUMN)
}

abstract class BaseCleanerCapable<T : CleanerColumnsBase>(
    private val repository: Repository<T>
) : CleanerCapabilities<T> {

    val ResultSet.runStatus get() = RunStatus.valueOf(getString(RUN_STATUS_COLUMN))

    val mapping: Map<String, (PreparedStatement, T, Int) -> Unit> = mapOf(
        RUN_STATUS_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setString(idx, entity.runStatus.name)
        },
        RUN_AT_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int ->
            stmt.setTimestamp(idx, entity.runAt?.toJavaInstant()?.let { Timestamp.from(it) })
        }
    )

    override suspend fun findEntitiesToDelete(
        cutoffDate: Instant,
        limit: Int,
        connection: Connection?
    ): List<T> = repository.withConnection(connection) {
        it.prepareStatement(findEntitiesToDeleteSQL).use { stmt ->
            stmt.apply {
                setTimestamp(1, Timestamp.from(cutoffDate.toJavaInstant()))
                setInt(2, limit)
            }

            stmt.executeQuery().use { rs -> with(repository) { rs.toModels() } }
        }
    }

    private val findEntitiesToDeleteSQL by lazy {
        """
            SELECT * FROM ${repository.tableName}
            WHERE $RUN_STATUS_COLUMN = '$DONE'
            AND $RUN_AT_COLUMN <= ?
            ORDER BY $RUN_AT_COLUMN ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
    }
}
