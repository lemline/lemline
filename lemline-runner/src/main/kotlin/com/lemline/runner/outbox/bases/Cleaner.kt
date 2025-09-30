// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.common.logger.logger
import com.lemline.runner.config.LemlineConfiguration.TablesConfig.CleanerConfig
import com.lemline.runner.models.bases.CleanerColumnsBase
import com.lemline.runner.repositories.bases.CleanerRepositoryBase
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Represents a task that periodically cleans up records from a database table to prevent storage bloat.
 *
 * The `Cleaner` class leverages a repository to execute cleanup operations in a batched and transactional manner.
 * The behavior and configuration are managed via a `CleanerConfig` object, which specifies cleanup interval (`every`),
 * maximum batch size, grace period, and an optional activation flag.
 *
 * @param T The type parameter representing an entity that implements `CleanerColumnsBase`,
 *          which defines the structure and status of the records eligible for cleanup.
 * @property description A textual description of the cleaner job.
 * @property cleanerRepository The repository used to fetch and delete records for cleanup.
 * @property cleanerConfig The configuration that defines the cleanup task's behavior.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal class Cleaner<T : CleanerColumnsBase>(
    private val cleanerRepository: CleanerRepositoryBase<T>,
    val cleanerConfig: CleanerConfig,
) : Schedulable {

    override val logger = logger()
    override val every: Duration = cleanerConfig.every
    override val gracePeriod: Duration = cleanerConfig.gracePeriod
    override val isEnabled: Boolean = cleanerConfig.enabled().getOrNull() != false

    /**
     * Cleans up old sent messages from the outbox table.
     * This method helps prevent database bloat by removing messages that:
     * 1. Have been successfully processed (status = SENT)
     * 2. Are older than the specified retention period
     *
     * The cleanup is performed in batches to:
     * - Prevent long-running transactions
     * - Reduce database locks
     * - Maintain system performance
     */
    override suspend fun run() = try {

        val cutoffDate = Clock.System.now() - every

        var totalToDelete = 0
        var totalDeleted = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toDelete = 0
            // Find and delete messages in the same transaction
            cleanerRepository.withTransaction { connection ->
                val entities = cleanerRepository.findEntitiesToDelete(cutoffDate, cleanerConfig.batchSize, connection)
                toDelete = entities.size

                if (toDelete > 0) {
                    totalToDelete += toDelete
                    val deleted = cleanerRepository.delete(entities, connection)
                    totalDeleted += deleted
                }
            }
        } while (toDelete >= cleanerConfig.batchSize)

        logger.logBatches(totalDeleted, totalToDelete, batchNumber, "deleted")
    } catch (e: Exception) {
        logger.error(e) { "💥 Error during scheduled outbox cleanup" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
    }
}

