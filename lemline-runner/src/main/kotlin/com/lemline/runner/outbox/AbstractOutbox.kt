// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.error
import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.toDuration
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.repositories.OutboxRepository
import io.quarkus.smallrye.reactivemessaging.sendSuspending
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.slf4j.Logger

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
 * @see OutboxProcessor for the core message processing logic
 */
internal abstract class AbstractOutbox<T : OutboxModel>() {
    protected val logger: Logger by lazy { logger() }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected abstract val enabled: Boolean


    protected abstract val repository: OutboxRepository<T>
    protected abstract val emitter: Emitter<String>

    protected abstract val outboxConf: LemlineConfiguration.OutboxProcessingConfig
    protected val processingBatchSize by lazy { outboxConf.batchSize() }
    protected val processingMaxAttempts by lazy { outboxConf.maxAttempts() }
    protected val processingInitialDelayAttempt by lazy { outboxConf.initialDelay() }
    protected val processingPeriod by lazy { outboxConf.every() }

    protected abstract val cleanupConf: LemlineConfiguration.OutboxCleanupConfig
    protected val cleanupAfter by lazy { cleanupConf.after() }
    protected val cleanupBatchSize by lazy { cleanupConf.batchSize() }
    protected val cleanupPeriod by lazy { cleanupConf.every() }

    private val outboxProcessor by lazy {
        OutboxProcessor(
            logger = logger,
            repository = repository,
            processor = ::process,
        )
    }

    private val outboxProcessingExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxProcessing = AtomicBoolean(false)

    private val outboxCleaningExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxCleaning = AtomicBoolean(false)

    open suspend fun process(entity: T) {
        emitter.sendSuspending(entity.message)
        entity.status = OutBoxStatus.SENT
    }

    @PostConstruct
    fun init() {
        if (!enabled) {
            logger.debug("🚫 Outbox disabled by config")
            return
        }

        // Schedule outbox processing
        val outboxPeriodSeconds = processingPeriod.toDuration().toSeconds()
        outboxProcessingExecutor.scheduleAtFixedRate(
            { scope.launch { outbox() } },
            0,
            outboxPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info("⏱️ Outbox processing scheduled every ${outboxPeriodSeconds}s")

        // Schedule cleanup
        val cleanupPeriodSeconds = cleanupPeriod.toDuration().toSeconds()
        outboxCleaningExecutor.scheduleAtFixedRate(
            { scope.launch { cleanup() } },
            0,
            cleanupPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info("⏱️ Outbox cleaning scheduled every ${cleanupPeriodSeconds}s")
    }

    @PreDestroy
    private fun shutdown() {
        logger.info("🛑 Shutting down outbox...")

        // Cancel coroutines
        scope.cancel()

        // Shutdown executors
        shutdownExecutor(outboxProcessingExecutor, "processing")
        shutdownExecutor(outboxCleaningExecutor, "cleaning")
    }

    /**
     * Safely executes the outbox task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private suspend fun outbox() {
        if (!outboxProcessing.compareAndSet(false, true)) {
            logger.warn("⏭ Skipping scheduled outbox processing: previous execution still running")
            return
        }

        try {
            outboxProcessor.process(
                processingBatchSize,
                processingMaxAttempts,
                processingInitialDelayAttempt.toDuration(),
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
        if (!outboxCleaning.compareAndSet(false, true)) {
            logger.warn("⏭ Skipping scheduled outbox cleaning: previous execution still running")
            return
        }

        try {
            outboxProcessor.cleanup(
                cleanupAfter.toDuration(),
                cleanupBatchSize,
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
    private fun shutdownExecutor(executor: java.util.concurrent.ScheduledExecutorService, name: String) {
        executor.shutdown() // stop accepting new tasks
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("⚠️ Forcing shutdown of outbox $name executor")
                executor.shutdownNow()
            } else {
                logger.info("✅ Outbox $name executor stopped gracefully")
            }
        } catch (_: InterruptedException) {
            // The current thread was interrupted while waiting
            logger.error("💥 Interrupted while shutting down outbox $name executor")
            executor.shutdownNow()
            Thread.currentThread().interrupt() // <- reassert the interrupt status
        }
    }
}
