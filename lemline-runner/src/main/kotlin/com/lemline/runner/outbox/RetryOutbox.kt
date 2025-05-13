// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.logger
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.LemlineConfiguration.RetryConfig
import com.lemline.runner.config.toDuration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * RetryOutbox is responsible for processing and managing retry messages in the system.
 * It handles two main operations:
 * 1. Processing pending retry messages and sending them to the workflow output channel
 * 2. Cleaning up old sent messages to prevent database bloat
 *
 * The class uses a scheduled approach with configurable intervals for both operations.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the same operation from running simultaneously.
 *
 * Configuration is managed through LemlineConfiguration, allowing for flexible tuning of:
 * - Processing batch size
 * - Maximum retry attempts
 * - Initial delay between retries
 * - Cleanup retention period
 *
 * @see OutboxProcessor for the core message processing logic
 * @see RetryConfig for configuration details
 */
@Startup
@ApplicationScoped
internal class RetryOutbox @Inject constructor(
    repository: RetryRepository,
    lemlineConfig: LemlineConfiguration,
    @Channel(WORKFLOW_OUT) private val emitter: Emitter<String>,
) {

    private val logger = logger()

    private val enabled = lemlineConfig.messaging().consumer().enabled()
    private val outboxConf = lemlineConfig.retry().outbox()
    private val cleaningConf = lemlineConfig.retry().cleanup()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { retryMessage -> emitter.send(retryMessage.message) },
    )

    private val outboxExecutor = Executors.newSingleThreadScheduledExecutor()
    private val cleaningExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxRunning = AtomicBoolean(false)
    private val cleaningRunning = AtomicBoolean(false)


    @PostConstruct
    fun init() {
        if (enabled) {
            val periodSeconds = outboxConf.every().toDuration().toSeconds()
            logger.info("⏱️ Schedule outbox task every ${periodSeconds}s")
            outboxExecutor.scheduleAtFixedRate(
                { safeOutbox() },
                0,
                periodSeconds,
                TimeUnit.SECONDS
            )
        } else {
            logger.debug("🚫 Outbox disabled by config")
        }

        if (enabled) {
            val periodSeconds = cleaningConf.every().toDuration().toSeconds()
            logger.info("⏱️ Schedule cleaning task every ${periodSeconds}s")
            cleaningExecutor.scheduleAtFixedRate(
                { safeCleaning() },
                0,
                periodSeconds,
                TimeUnit.SECONDS
            )
        } else {
            logger.debug("🚫 Cleaning task disabled by config")
        }
    }

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
     * Processes pending retry messages from the outbox table.
     * This method is scheduled to run at configurable intervals.
     *
     * For each batch of messages:
     * 1. Retrieves messages that are ready to process (status = PENDING)
     * 2. Attempts to send each message using the emitter
     * 3. Updates message status to SENT on success
     * 4. Handles retries on failure with exponential backoff
     *
     * The operation is transactional and thread-safe, ensuring that:
     * - Messages are processed exactly once
     * - Failed messages are properly tracked and retried
     * - Concurrent processing is prevented
     */
    private fun outbox() {
        outboxProcessor.process(
            outboxConf.batchSize(),
            outboxConf.maxAttempts(),
            outboxConf.initialDelay().toDuration(),
        )
    }

    /**
     * Cleans up old sent messages from the outbox table.
     * This method is scheduled to run at configurable intervals.
     *
     * For each batch of messages:
     * 1. Identifies messages that are:
     *    - Marked as SENT
     *    - Older than the configured retention period
     * 2. Deletes these messages in batches to prevent database locks
     *
     * The operation is transactional and thread-safe, ensuring that:
     * - Only sent messages are deleted
     * - Cleanup doesn't interfere with active message processing
     * - Database performance is maintained through batch processing
     */
    private fun cleanup() {
        outboxProcessor.cleanup(
            cleaningConf.after().toDuration(),
            cleaningConf.batchSize(),
        )
    }
}
