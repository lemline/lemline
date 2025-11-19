// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.common.logger.logger
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.AwaitingCompletionModel
import com.lemline.runner.repositories.CleanerRepository
import io.quarkus.runtime.ShutdownEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.event.Observes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * AbstractCleaner provides base functionality for scheduled cleanup operations.
 * This class handles the infrastructure for scheduling, executing, and gracefully shutting down
 * cleanup tasks. It can be extended by classes that need only cleanup (waiting entities) or
 * both cleanup and processing (outbox entities).
 *
 * The class uses a scheduled approach with configurable intervals for cleanup operations.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the cleanup operation from running simultaneously.
 *
 * This class provides a concrete implementation of cleanup logic that works with any
 * WaitingRepository. Subclasses only need to provide the repository, configuration,
 * and enabled flag.
 *
 * @param T The type of entity to clean up (must extend WaitingModel)
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractCleaner<T : AwaitingCompletionModel> {
    protected val logger by lazy { logger() }

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected abstract val enabled: Boolean
    protected abstract val cleanerConf: LemlineConfiguration.OutboxCleanupConfig
    protected abstract val cleanerRepository: CleanerRepository<T>

    protected val gracePeriod = 5000L

    protected val cleaningExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    protected val cleaning = AtomicBoolean(false)
    protected val isShuttingDown = AtomicBoolean(false)

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
            // WaitingRepository extends Repository, so withTransaction is available
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
        logger.error(e) { "💥 Error during scheduled cleanup" }
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

    private fun Int.entities(): String = this.toString() + " entity" + if (this <= 1) "" else " entities"
    private fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"

    @PostConstruct
    open fun init() {
        if (!enabled) {
            logger.debug { "🚫 Cleanup disabled by config" }
            return
        }

        scheduleCleanup()
    }

    /**
     * Schedules the cleanup task. Can be overridden by subclasses to add additional scheduling.
     */
    protected open fun scheduleCleanup() {
        val cleanupPeriodSeconds = cleanerConf.every.inWholeSeconds
        cleaningExecutor.scheduleAtFixedRate(
            { scope.launch { cleanup() } },
            0,
            cleanupPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info { "⏱️ Cleanup scheduled every ${cleanupPeriodSeconds}s" }
    }

    /**
     * Safely executes the cleaning task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    protected suspend fun cleanup() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping cleanup: shutdown in progress" }
            return
        }

        if (!cleaning.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping scheduled cleanup: previous execution still running" }
            return
        }

        try {
            doCleanup()
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during cleanup" }
        } finally {
            cleaning.set(false)
        }
    }


    // Quarkus will wait for the completion of performGracefulShutdown() before shutting down
    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "🛑 ShutdownEvent received - initiating graceful shutdown" }
        performGracefulShutdown(gracePeriod)
    }

    protected open fun performGracefulShutdown(timeoutMs: Long) {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.info { "🛑 Shutdown already in progress - ignoring" }
            return
        }

        logger.info { "🛑 Shutting down cleaner..." }

        // Shutdown executors - can be overridden to shutdown additional executors
        shutdownExecutors()

        // Wait for active coroutines to complete (non-blocking)
        gracefulWaitForCompletion(timeoutMs)
    }

    /**
     * Shuts down executors. Can be overridden by subclasses to shutdown additional executors.
     */
    protected open fun shutdownExecutors() {
        shutdownExecutor(cleaningExecutor, "cleaning")
    }

    protected fun gracefulWaitForCompletion(timeoutMs: Long) {
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    // Wait for all child coroutines to complete
                    scope.coroutineContext.job.children.forEach { it.join() }
                }
                logger.info { "✅ All scheduled cleanup tasks processed, completing shutdown" }
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn { "⚠️ Graceful shutdown timed out with tasks still being processed" }
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during graceful shutdown" }
        } finally {
            // Always cancel remaining coroutines
            scope.cancel()
            logger.info { "🏁 Waiting cleaner scope cancelled" }
        }
    }

    /**
     * Shuts down the given ScheduledExecutorService in a controlled manner.
     *
     * This method attempts to stop the executor gracefully within a timeout of 5 seconds, and if
     * this fails, it forces the termination of all tasks. It also accounts for interrupted exceptions,
     * ensuring the current thread's interrupt status is reasserted.
     */
    protected fun shutdownExecutor(executor: ScheduledExecutorService, name: String) {
        try {
            // Stop accepting new scheduled tasks
            executor.shutdown()

            if (!executor.awaitTermination(gracePeriod, TimeUnit.MILLISECONDS)) {
                logger.warn { "⚠️ Forcing shutdown of waiting $name executor" }
                executor.shutdownNow()
            } else {
                logger.info { "✅ Waiting $name executor stopped gracefully" }
            }
        } catch (_: InterruptedException) {
            // The current thread was interrupted while waiting
            logger.error { "💥 Interrupted while shutting down waiting $name executor" }
            executor.shutdownNow()
            Thread.currentThread().interrupt() // <- reassert the interrupt status
        }
    }
}
