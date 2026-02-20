// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import com.lemline.runner.common.models.WithCleanup
import java.sql.Connection
import kotlin.time.Instant

/**
 * Interface for repositories that support cleanup operations.
 * Implemented by repositories with cleanerOps composition.
 *
 * Note: This interface is meant to be implemented by classes that extend Repository,
 * which provides delete implementation.
 */
interface WithCleanerRepository<T : WithCleanup> {
    /**
     * Find entities ready for cleanup (cleanup_after < cutoffDate).
     *
     * @param cutoffDate The cutoff timestamp - entities with cleanup_after before this are eligible
     * @param batchSize Maximum number of entities to return
     * @param connection Optional database connection to use
     * @return List of entities ready for cleanup
     */
    suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection? = null): List<T>
}
