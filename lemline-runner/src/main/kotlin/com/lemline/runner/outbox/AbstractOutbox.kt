// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.logger.logger
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
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
 * AbstractOutbox provides base functionality for outbox pattern implementations.
 * It handles scheduling and execution of two primary operations:
 * 1. Processing pending messages and sending them to the workflow output channel
 * 2. Cleaning up old sent messages to prevent database bloat
 *
 * The class uses a scheduled approach with configurable intervals for both operations.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the same operation from running simultaneously.
 *
 * @param T Type of the message entity (must implement OutboxModel interface)
 * @see OutboxRelay for the core message processing logic
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractOutbox<T : OutboxModel>() {
    protected val logger by lazy { logger() }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected abstract val enabled: Boolean

    protected abstract val failureRepository: FailureRepository
    protected abstract val outboxRepository: OutboxRepository<T>
    protected abstract val instanceEmitter: InstanceMessageEmitter

    protected abstract val outboxConf: LemlineConfiguration.OutboxProcessingConfig
    protected abstract val cleanupConf: LemlineConfiguration.OutboxCleanupConfig

    private val gracePeriod = 5000L

    private val outboxRelay by lazy {
        OutboxRelay(
            logger = logger,
            failureRepository = failureRepository,
            outboxRepository = outboxRepository,
            relay = ::process,
        )
    }

    private val outboxProcessingExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxProcessing = AtomicBoolean(false)

    private val outboxCleaningExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxCleaning = AtomicBoolean(false)

    private val isShuttingDown = AtomicBoolean(false)

    open suspend fun process(entity: T) {
        instanceEmitter.send(entity.instanceMessage)
    }

    @PostConstruct
    fun init() {
        if (!enabled) {
            logger.debug { "🚫 Outbox disabled by config" }
            return
        }

        // Schedule outbox processing
        val outboxPeriodSeconds = outboxConf.every.inWholeSeconds
        outboxProcessingExecutor.scheduleAtFixedRate(
            { scope.launch { outbox() } },
            0,
            outboxPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info { "⏱️ Outbox processing scheduled every ${outboxPeriodSeconds}s" }

        // Schedule cleanup
        val cleanupPeriodSeconds = cleanupConf.every.inWholeSeconds
        outboxCleaningExecutor.scheduleAtFixedRate(
            { scope.launch { cleanup() } },
            0,
            cleanupPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info { "⏱️ Outbox cleaning scheduled every ${cleanupPeriodSeconds}s" }
    }

    // Quarkus will wait for the completion of performGracefulShutdown() before shutting down
    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "🛑 ShutdownEvent received - initiating graceful shutdown" }
        performGracefulShutdown(gracePeriod)
    }

    private fun performGracefulShutdown(timeoutMs: Long) {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.info { "🛑 Shutdown already in progress - ignoring" }
            return
        }

        logger.info { "🛑 Shutting down outbox..." }

        // Shutdown executors first (prevents new coroutine launches)
        shutdownExecutor(outboxProcessingExecutor, "processing")
        shutdownExecutor(outboxCleaningExecutor, "cleaning")

        // Wait for active coroutines to complete (non-blocking)
        gracefulWaitForCompletion(timeoutMs)
    }

    private fun gracefulWaitForCompletion(timeoutMs: Long) {
        // Use a separate thread to avoid blocking @PreDestroy
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    // Wait for all child coroutines to complete
                    scope.coroutineContext.job.children.forEach { it.join() }
                }
                logger.info { "✅ All scheduled tasks processed, completing shutdown" }
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn { "⚠️ Graceful shutdown timed out with tasks still being processed" }
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during graceful shutdown" }
        } finally {
            // Always cancel remaining coroutines
            scope.cancel()
            logger.info { "🏁 Outbox scope cancelled" }
        }
    }

    /**
     * Safely executes the outbox task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private suspend fun outbox() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping outbox processing: shutdown in progress" }
            return
        }

        if (!outboxProcessing.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping scheduled outbox processing: previous execution still running" }
            return
        }

        try {
            outboxRelay.process(
                batchSize = outboxConf.batchSize,
                maxAttempts = outboxConf.maxAttempts,
                initialDelay = outboxConf.initialDelay,
            )
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during outbox processing" }
        } finally {
            outboxProcessing.set(false)
        }
    }

    /**
     * Safely executes the cleaning task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private suspend fun cleanup() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping outbox cleaning: shutdown in progress" }
            return
        }


        if (!outboxCleaning.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping scheduled outbox cleaning: previous execution still running" }
            return
        }

        try {
            outboxRelay.cleanup(
                afterDelay = cleanupConf.after,
                batchSize = cleanupConf.batchSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during outbox cleaning" }
        } finally {
            outboxCleaning.set(false)
        }
    }

    /**
     * Shuts down the given ScheduledExecutorService in a controlled manner.
     *
     * This method attempts to stop the executor gracefully within a timeout of 5 seconds, and if
     * this fails, it forces the termination of all tasks. It also accounts for interrupted exceptions,
     * ensuring the current thread's interrupt status is reasserted.
     */
    private fun shutdownExecutor(executor: ScheduledExecutorService, name: String) {
        try {
            //Stop accepting new scheduled tasks
            executor.shutdown()

            if (!executor.awaitTermination(gracePeriod, TimeUnit.MILLISECONDS)) {
                logger.warn { "⚠️ Forcing shutdown of outbox $name executor" }
                executor.shutdownNow()
            } else {
                logger.info { "✅ Outbox $name executor stopped gracefully" }
            }
        } catch (_: InterruptedException) {
            // The current thread was interrupted while waiting
            logger.error { "💥 Interrupted while shutting down outbox $name executor" }
            executor.shutdownNow()
            Thread.currentThread().interrupt() // <- reassert the interrupt status
        }
    }
}
