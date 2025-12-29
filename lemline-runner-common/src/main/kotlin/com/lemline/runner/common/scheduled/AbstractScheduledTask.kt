// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.scheduled

import com.lemline.common.logger.logger
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
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

/**
 * Abstract base class for scheduled background tasks.
 *
 * Provides common infrastructure for:
 * - Scheduled execution at configurable intervals
 * - Concurrent execution prevention (SKIP strategy - if previous run is still executing, skip the new run)
 * - Graceful shutdown handling
 * - Coroutine-based async execution
 *
 * ## Usage
 *
 * Subclasses must implement:
 * - [enabled]: Whether this task should run
 * - [interval]: How often to run (e.g., `10.seconds`)
 * - [jobName]: Human-readable name for logging
 * - [doWork]: The actual work to perform
 *
 * ## Example
 *
 * ```kotlin
 * @Startup
 * @ApplicationScoped
 * class MyScheduledTask : AbstractScheduledTask() {
 *     override val enabled by lazy { config.myTask().enabled() }
 *     override val interval by lazy { config.myTask().every }
 *     override val taskName = "my-task"
 *
 *     override suspend fun doWork() {
 *         // Your scheduled work here
 *     }
 * }
 * ```
 *
 * @see com.lemline.runner.common.cleaner.AbstractCleaner for cleanup-specific scheduling
 * @see com.lemline.runner.common.outbox.AbstractOutbox for outbox processing with cleanup
 */
@ExperimentalTime
abstract class AbstractScheduledTask {
    protected val logger by lazy { logger() }

    /** Coroutine scope for async work */
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Whether this scheduled task is enabled */
    protected abstract val enabled: Boolean

    /** How often to run this task */
    protected abstract val interval: Duration

    /** Human-readable name for logging */
    protected abstract val jobName: String

    /** Grace period for shutdown (ms) */
    protected open val gracePeriod = 5000L

    /** Initial delay before first execution (0 = run immediately) */
    protected open val initialDelay: Long = 0L

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val isRunning = AtomicBoolean(false)
    protected val isShuttingDown = AtomicBoolean(false)

    /**
     * The work to perform on each scheduled execution.
     * Implement this method with your task logic.
     */
    protected abstract suspend fun doWork()

    /**
     * Called when Quarkus application starts, after database migrations complete.
     * Priority 100 ensures this runs after FlywayMigration (priority 1).
     */
    @Suppress("unused")
    fun onStart(@Observes @Priority(100) event: StartupEvent) {
        if (!enabled) {
            logger.debug { "$jobName disabled by config" }
            return
        }

        scheduleTask()
    }

    /**
     * Schedules the main task. Can be overridden by subclasses to add additional scheduling.
     */
    protected open fun scheduleTask() {
        val intervalSeconds = interval.inWholeSeconds
        executor.scheduleAtFixedRate(
            { scope.launch { execute() } },
            initialDelay,
            intervalSeconds,
            TimeUnit.SECONDS
        )
        logger.info { "$jobName scheduled every ${intervalSeconds}s" }
    }

    /**
     * Executes the task with concurrent execution prevention.
     * If a previous execution is still running, this execution is skipped.
     */
    private suspend fun execute() {
        if (isShuttingDown.get()) {
            logger.debug { "Skipping $jobName: shutdown in progress" }
            return
        }

        if (!isRunning.compareAndSet(false, true)) {
            logger.debug { "Skipping $jobName: previous execution still running" }
            return
        }

        try {
            doWork()
        } catch (e: Exception) {
            logger.error(e) { "Error during $jobName execution" }
        } finally {
            isRunning.set(false)
        }
    }

    @Suppress("unused")
    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.debug { "ShutdownEvent received for $jobName - initiating graceful shutdown" }
        performGracefulShutdown()
    }

    protected open fun performGracefulShutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.debug { "Shutdown already in progress for $jobName - ignoring" }
            return
        }

        logger.debug { "Shutting down $jobName..." }

        // Shutdown executors
        shutdownExecutor(executor, jobName)

        // Wait for active coroutines to complete
        gracefulWaitForCompletion()
    }

    /**
     * Waits for active coroutines to complete within the grace period.
     */
    protected fun gracefulWaitForCompletion() {
        try {
            runBlocking {
                withTimeout(gracePeriod) {
                    scope.coroutineContext.job.children.forEach { it.join() }
                }
                logger.debug { "All $jobName jobs completed" }
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn { "Graceful shutdown timed out for $jobName - jobs still running" }
        } catch (e: Exception) {
            logger.error(e) { "Error during $jobName graceful shutdown" }
        } finally {
            scope.cancel()
            logger.debug { "$jobName scope cancelled" }
        }
    }

    /**
     * Shuts down the given ScheduledExecutorService in a controlled manner.
     *
     * This method attempts to stop the executor gracefully within the grace period, and if
     * this fails, it forces the termination of all tasks.
     */
    protected fun shutdownExecutor(executor: ScheduledExecutorService, name: String) {
        try {
            executor.shutdown()

            if (!executor.awaitTermination(gracePeriod, TimeUnit.MILLISECONDS)) {
                logger.warn { "Forcing shutdown of $name executor" }
                executor.shutdownNow()
            } else {
                logger.debug { "$name executor stopped gracefully" }
            }
        } catch (_: InterruptedException) {
            logger.error { "Interrupted while shutting down $name executor" }
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
