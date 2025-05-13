// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.logger
import com.lemline.runner.config.toDuration
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.repositories.OutboxRepository
import jakarta.annotation.PostConstruct
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    protected abstract val enabled: Boolean

    protected abstract val outboxBatchSize: Int
    protected abstract val outboxMaxAttempts: Int
    protected abstract val outboxInitialDelay: String
    protected abstract val outboxExecutionPeriod: String

    protected abstract val cleanupAfter: String
    protected abstract val cleanupBatchSize: Int
    protected abstract val cleanupExecutionPeriod: String

    protected abstract val repository: OutboxRepository<T>
    protected abstract val emitter: Emitter<String>

    private val outboxProcessor by lazy {
        OutboxProcessor(
            logger = logger,
            repository = repository,
            processor = { message -> emitter.send(message.message) },
        )
    }

    private val outboxExecutor = Executors.newSingleThreadScheduledExecutor()
    private val cleaningExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxRunning = AtomicBoolean(false)
    private val cleaningRunning = AtomicBoolean(false)

    @PostConstruct
    fun init() {
        if (!enabled) {
            logger.debug("🚫 Outbox disabled by config")
            return
        }

        if (outboxProcessor == null) {
            logger.warn("⚠️ Outbox processor not initialized, skipping scheduling")
            return
        }

        // Schedule outbox processing
        val outboxPeriodSeconds = outboxExecutionPeriod.toDuration().toSeconds()
        outboxExecutor.scheduleAtFixedRate(
            { safeOutbox() },
            0,
            outboxPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info("⏱️ Outbox scheduled every ${outboxPeriodSeconds}s")

        // Schedule cleanup
        val cleanupPeriodSeconds = cleanupExecutionPeriod.toDuration().toSeconds()
        cleaningExecutor.scheduleAtFixedRate(
            { safeCleaning() },
            0,
            cleanupPeriodSeconds,
            TimeUnit.SECONDS
        )
        logger.info("⏱️ Cleaning scheduled every ${cleanupPeriodSeconds}s")
    }

    /**
     * Safely executes the outbox task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private fun safeOutbox() {
        if (!outboxRunning.compareAndSet(false, true)) {
            logger.warn("⏭ Skipping execution: outbox task still running")
            return
        }

        try {
            outbox()
        } catch (ex: Exception) {
            logger.error("💥 Error in outbox task", ex)
        } finally {
            outboxRunning.set(false)
        }
    }

    /**
     * Safely executes the cleaning task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private fun safeCleaning() {
        if (!cleaningRunning.compareAndSet(false, true)) {
            logger.warn("⏭ Skipping execution: cleaning task still running")
            return
        }

        try {
            cleanup()
        } catch (ex: Exception) {
            logger.error("💥 Error in cleaning task", ex)
        } finally {
            cleaningRunning.set(false)
        }
    }

    /**
     * Processes pending messages from the outbox table.
     */
    private fun outbox() {
        outboxProcessor?.process(
            outboxBatchSize,
            outboxMaxAttempts,
            outboxInitialDelay.toDuration(),
        )
    }

    /**
     * Cleans up old sent messages from the outbox table.
     */
    private fun cleanup() {
        outboxProcessor?.cleanup(
            cleanupAfter.toDuration(),
            cleanupBatchSize,
        )
    }
}
