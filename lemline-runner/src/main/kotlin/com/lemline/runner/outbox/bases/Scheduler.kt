// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.runner.config.DATABASE_CONSUMER_ENABLED
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
 * Abstract class that defines a task scheduler designed to run periodic tasks
 * with support for graceful shutdowns and concurrency management.
 *
 * @property description A textual description of the scheduled task, used for logging purposes.
 */
@ExperimentalTime
@ExperimentalSerializationApi
abstract class Scheduler {
    abstract val schedulable: Schedulable

    abstract val description: String

    private val logger by lazy { schedulable.logger }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val isExecuting = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    @PostConstruct
    fun init() {
        // Quarkus started without listening
        if (System.getProperty(DATABASE_CONSUMER_ENABLED) == "false") return

        if (!schedulable.isEnabled) {
            logger.debug { "🚫 $description disabled by config" }
            return
        }

        executor.scheduleAtFixedRate(
            { scope.launch { run() } },
            0,
            schedulable.every.inWholeMilliseconds,
            TimeUnit.MILLISECONDS
        )
        logger.info { "⏱️ $description scheduled every ${schedulable.every.inWholeSeconds}s" }
    }

    // Quarkus will wait for the completion of onQuarkusShutdown() before actually shutting down
    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        performGracefulShutdown()
    }

    private fun performGracefulShutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.info { "🛑 $description: shutdown already in progress" }
            return
        }

        logger.info { "🛑 $description: initiating graceful shutdown..." }

        // Shutdown executors first (prevents new coroutine launches)
        shutdownExecutor(executor)

        // Wait for active coroutines to complete (non-blocking)
        gracefulWaitForCompletion()
    }

    /**
     * Gracefully waits for the completion of all active child coroutines within the specified grace period.
     *
     * Ensures that child coroutines of the specified scope are given a chance to finish before
     * cancellation occurs. If the operations cannot be completed within the allocated timeout, they will be
     * forcefully aborted.
     */
    private fun gracefulWaitForCompletion() = try {
        runBlocking {
            withTimeout(schedulable.gracePeriod.inWholeMilliseconds) {
                // Wait for all child coroutines to complete
                scope.coroutineContext.job.children.forEach { it.join() }
            }
            logger.info { "✅ $description completed, continuing with shutdown" }
        }
    } catch (_: TimeoutCancellationException) {
        logger.warn { "⚠️ Time out during graceful shutdown of $description" }
    } catch (e: Exception) {
        logger.error(e) { "💥 Error during the graceful shutdown of $description" }
    } finally {
        // Always cancel remaining coroutines
        scope.cancel()
        logger.info { "🏁 scope of $description cancelled" }
    }

    /**
     * Safely executes the cleaning task while ensuring that no concurrent executions occur.
     */
    private suspend fun run() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping $description as a shutdown is in progress" }
            return
        }

        if (!isExecuting.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping $description as the previous execution is still running" }
            return
        }

        try {
            schedulable.run()
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during $description" }
        } finally {
            isExecuting.set(false)
        }
    }

    /**
     * Shuts down the given ScheduledExecutorService in a controlled manner.
     *
     * This method attempts to stop the executor gracefully within a grace period, and if
     * this fails, it forces the termination of all tasks. It also accounts for interrupted exceptions,
     * ensuring the current thread's interrupt status is reasserted.
     */
    private fun shutdownExecutor(executor: ScheduledExecutorService) {
        try {
            //Stop accepting new scheduled tasks
            executor.shutdown()

            if (!executor.awaitTermination(schedulable.gracePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                logger.warn { "⚠️ Forcing shutdown of $description executor" }
                executor.shutdownNow()
            } else {
                logger.info { "✅ $description executor stopped gracefully" }
            }
        } catch (_: InterruptedException) {
            // The current thread was interrupted while waiting
            logger.error { "💥 Interrupted while shutting down $description executor" }
            executor.shutdownNow()
            Thread.currentThread().interrupt() // <- reassert the interrupt status
        }
    }
}
