// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.AwaitingCompletionModel
import com.lemline.runner.repositories.CleanerRepository
import com.lemline.runner.scheduled.AbstractScheduledTask
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * AbstractCleaner provides base functionality for scheduled cleanup operations.
 *
 * This class extends [AbstractScheduledTask] and adds cleanup-specific functionality:
 * - Batch deletion of old entities based on a configurable cutoff date
 * - Works with any [CleanerRepository] implementation
 *
 * Subclasses only need to provide:
 * - [enabled] - whether cleanup is enabled
 * - [cleanerConf] - cleanup configuration (interval, retention period, batch size)
 * - [cleanerRepository] - repository for the entity type to clean
 *
 * @param T The type of entity to clean up (must extend AwaitingCompletionModel)
 * @see AbstractScheduledTask for the scheduling infrastructure
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractCleaner<T : AwaitingCompletionModel> : AbstractScheduledTask() {

    protected abstract val cleanerConf: LemlineConfiguration.OutboxCleanupConfig
    protected abstract val cleanerRepository: CleanerRepository<T>

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
     * Cleans up old entities in batches to prevent long-running transactions.
     */
    protected suspend fun doCleanup() = try {
        val cutoffDate = kotlin.time.Clock.System.now() - cleanerConf.after

        var totalToDelete = 0
        var totalDeleted = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toDelete = 0
            cleanerRepository.withTransaction { connection ->
                val entities = cleanerRepository.findEntitiesToDelete(cutoffDate, cleanerConf.batchSize, connection)
                toDelete = entities.size

                if (toDelete > 0) {
                    totalToDelete += toDelete
                    val deleted = cleanerRepository.delete(entities, connection)
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
