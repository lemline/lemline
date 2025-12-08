// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.WithCleanup
import com.lemline.runner.repositories.with.WithCleanerRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import com.lemline.runner.scheduled.AbstractScheduledTask
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * AbstractCleaner provides base functionality for scheduled cleanup operations.
 *
 * This class extends [AbstractScheduledTask] and adds cleanup-specific functionality:
 * - Batch deletion of entities where cleanup_after < cutoffDate
 * - Works with any [WithCleanerRepository] implementation
 *
 * Subclasses only need to provide:
 * - [enabled] - whether cleanup is enabled
 * - [cleanerConf] - cleanup configuration (interval, batch size, retention period)
 * - [cleanerRepository] - repository for the entity type to clean
 *
 * The retention period is applied at query time: entities are eligible for cleanup
 * when `cleanup_after < (now - cleanerConf.after)`. This allows changing retention
 * policy without updating existing records.
 *
 * @param T The type of entity to clean up (must implement WithCleanup)
 * @see AbstractScheduledTask for the scheduling infrastructure
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractCleaner<T : WithCleanup> : AbstractScheduledTask() {

    protected abstract val cleanerConf: LemlineConfiguration.OutboxCleanupConfig
    protected abstract val cleanerRepository: WithCleanerRepository<T>
    protected abstract val crudRepository: WithCrudRepository<T>
    protected abstract val databaseManager: DatabaseManager

    override val taskName: String get() = "Cleaner"

    override val interval: Duration get() = cleanerConf.every

    /**
     * Performs the cleanup by calling [doCleanup].
     */
    override suspend fun doWork() {
        doCleanup()
    }

    /**
     * Performs the actual cleanup logic using the repository.
     * Cleans up entities where cleanup_after < cutoffDate in batches to prevent long-running transactions.
     * The cutoffDate is calculated as (now - retention period) to honor the configured retention.
     */
    protected suspend fun doCleanup() = try {
        var totalToDelete = 0
        var totalDeleted = 0
        var batchNumber = 0

        // Calculate cutoff date by applying retention offset
        val cutoffDate = Clock.System.now() - cleanerConf.after

        do {
            batchNumber++
            var toDelete = 0
            databaseManager.withTransaction { connection ->
                val entities = cleanerRepository.findEntitiesToDelete(cutoffDate, cleanerConf.batchSize, connection)
                toDelete = entities.size

                if (toDelete > 0) {
                    totalToDelete += toDelete
                    val deleted = crudRepository.delete(entities, connection)
                    totalDeleted += deleted
                }
            }
        } while (toDelete >= cleanerConf.batchSize)

        logCleanupResults(totalDeleted, totalToDelete, batchNumber)
    } catch (e: Exception) {
        logger.error(e) { "Error during scheduled cleanup" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
    }

    protected open fun logCleanupResults(success: Int, total: Int, batchNumber: Int) {
        val failed = total - success
        when (total) {
            0 -> logger.debug { "No entity found to clean" }
            else -> when {
                failed == 0 -> logger.debug { "All ${total.entities()} deleted successfully (over ${batchNumber.batches()})" }
                else -> logger.debug { "${success.entities()} deleted successfully and ${failed.entities()} failed (over ${batchNumber.batches()})" }
            }
        }
    }

    protected fun Int.entities(): String = this.toString() + " entity" + if (this <= 1) "" else " entities"
    protected fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"
}
